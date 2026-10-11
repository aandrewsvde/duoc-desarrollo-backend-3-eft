# Instrucciones de ejecución y prueba

Banco XYZ · Evaluación Final Transversal · Desarrollo Backend III (PBY2203)
Agustín Andrews V.

Este documento explica cómo levantar el sistema y cómo comprobar cada
componente. Está pensado para que cualquiera pueda reproducir los resultados sin
conocer el código.

---

## 1. Requisitos

| Herramienta | Versión | Por qué |
|---|---|---|
| Docker | 24 o superior | Ejecuta los quince contenedores |
| Docker Compose | v2 | Orquesta el arranque y el orden de dependencias |
| `curl` y `python3` | cualquiera reciente | Solo los usan los guiones de prueba |

**No hace falta Java ni Maven.** La compilación ocurre dentro de la imagen de
construcción, de modo que el único requisito real es Docker.

Puertos que deben estar libres:

```
5436  PostgreSQL          8080  gateway            8761  Eureka
8081  ms-cuentas          8082  ms-pagos           8083  ms-notificaciones
8084  ms-clientes         8085  batch              8888  config-server
8090  panel de Kafka      8091  BFF web            8092  BFF móvil
8093  BFF cajero          9000  authorization      9092/29092  Kafka
```

---

## 2. Levantar el sistema

```bash
./scripts/levantar.sh
```

El guion construye las imágenes, levanta los contenedores y **espera a que el
sistema esté realmente operativo**. Esa espera no es cosmética: que un
contenedor esté «arriba» no significa que su servicio responda, y el gateway
guarda una copia local del registro de Eureka que tarda unos segundos en
refrescarse. Sin esperar, las primeras peticiones devuelven 503 y parece un
fallo cuando no lo es.

Tarda entre 3 y 6 minutos la primera vez, casi todo en descargar dependencias
de Maven. Las siguientes, bajo un minuto.

### Migrar los datos del sistema legado

El sistema arranca **vacío**. Los datos los pone la migración batch:

```bash
./scripts/migrar.sh
```

Esto ejecuta los tres jobs de Spring Batch en orden y carga el resultado
saneado en las bases de los tres microservicios. Al terminar imprime cuántos
clientes, cuentas, movimientos y pagos quedaron.

### Detener

```bash
./scripts/detener.sh             # conserva los datos
./scripts/detener.sh --limpiar   # elimina también el volumen de PostgreSQL
```

---

## 3. Direcciones

| Qué | Dónde |
|---|---|
| Gateway (única puerta de entrada) | <http://localhost:8080> |
| Servidor de autorización OAuth 2.0 | <http://localhost:9000> |
| Panel de Eureka | <http://localhost:8761> |
| Configuración centralizada | <http://localhost:8888/ms-pagos/default> |
| Panel de Kafka | <http://localhost:8090> |
| Lanzador del batch | <http://localhost:8085/api/batch/ejecuciones> |
| PostgreSQL | `localhost:5436`, usuario y clave `bancoxyz` |

Swagger de cada servicio: `http://localhost:<puerto>/swagger-ui.html` con los
puertos 8081 (cuentas), 8082 (pagos), 8083 (notificaciones), 8084 (clientes),
8091 (web), 8092 (móvil) y 8093 (cajero).

---

## 4. Credenciales

### Clientes OAuth 2.0 de máquina

Cada canal tiene **su propio cliente**, con credenciales y alcances distintos.
No es burocracia: el token del cajero no sirve en el móvil ni al revés, de modo
que comprometer un canal no abre los otros dos.

| `client_id` | Secreto | Alcances |
|---|---|---|
| `canal-web` | `canal-web-secreto-2026` | `clientes.leer` `cuentas.leer` `cuentas.escribir` `pagos.leer` `pagos.escribir` |
| `canal-movil` | `canal-movil-secreto-2026` | `clientes.leer` `cuentas.leer` `pagos.leer` |
| `canal-cajero` | `canal-cajero-secreto-2026` | `cuentas.leer` `pagos.leer` `pagos.escribir` |
| `banco-servicios` | `banco-servicios-secreto-2026` | `clientes.leer` `cuentas.leer` `pagos.leer` |

> El móvil **no** tiene `pagos.escribir`: la aplicación muestra saldos, y toda
> operación que mueva dinero pasa por otro canal. El cajero **no** tiene
> `clientes.leer`: es un equipo en la vía pública y no tiene por qué poder
> consultar el padrón de titulares.

### Usuario de persona (flujo con navegador)

| Usuario | Clave | Roles |
|---|---|---|
| `cliente` | `cliente123` | `ROLE_CLIENTE` |
| `ejecutivo` | `ejecutivo123` | `ROLE_EJECUTIVO`, `ROLE_CLIENTE` |

Cliente registrado para ese flujo: `banco-web` / `banco-web-secreto-2026`,
con `authorization_code` + PKCE y redirección a
`http://127.0.0.1:9000/autorizado`.

---

## 5. Obtener un token

```bash
TOKEN=$(./scripts/obtener-token.sh canal-web)
```

El guion acepta `canal-web`, `canal-movil`, `canal-cajero` o `banco-servicios`
y pide por cada uno los alcances que le corresponden. Para ver qué lleva dentro
el token:

```bash
./scripts/lib/ver-token.py "$TOKEN"
```

Un JWT **no está cifrado, está firmado**: cualquiera puede leer su contenido, y
lo que la firma garantiza es que nadie lo ha modificado.

---

## 6. Probar cada componente

### 6.1 El gateway exige credenciales

```bash
curl -i http://localhost:8080/api/cuentas/101        # 401
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/cuentas/101
```

El tráfico sin credenciales no llega siquiera a la red interna.

### 6.2 Parte 1 — migración batch

```bash
# Lanzar un job concreto
curl -X POST http://localhost:8085/api/batch/jobs/intereses
curl -X POST http://localhost:8085/api/batch/jobs/estados
curl -X POST http://localhost:8085/api/batch/jobs/transacciones

# O los tres en el orden correcto
curl -X POST http://localhost:8085/api/batch/migracion-completa

# Historial de ejecuciones
curl http://localhost:8085/api/batch/ejecuciones | python3 -m json.tool
```

> **Los tres jobs forman una unidad.** El de intereses vacía las tablas de
> destino antes de cargarlas, de modo que ejecutarlo solo borraría los
> movimientos que cargó el de estados. Para una migración normal, use
> `/migracion-completa`.

Los informes quedan en `datos/salida/informes/`. Los registros rechazados, con
su motivo, en la tabla `registro_rechazado` de la base `bancoxyz_batch`:

```bash
docker compose exec postgres psql -U bancoxyz -d bancoxyz_batch -c \
  "SELECT job_name, fase, left(motivo,60), count(*) FROM registro_rechazado
   GROUP BY 1,2,3 ORDER BY 4 DESC LIMIT 10;"
```

### 6.3 Parte 2 — los tres canales (BFF)

El mismo titular por los tres canales, para ver la diferencia:

```bash
TW=$(./scripts/obtener-token.sh canal-web)
TM=$(./scripts/obtener-token.sh canal-movil)
TC=$(./scripts/obtener-token.sh canal-cajero)

# Vista completa
curl -H "Authorization: Bearer $TW" http://localhost:8080/canal/web/panel/6

# Lo mínimo que sirve
curl -H "Authorization: Bearer $TM" http://localhost:8080/canal/movil/resumen/6

# Solo lo que un cajero necesita
curl -H "Authorization: Bearer $TC" http://localhost:8080/canal/cajero/saldo/146
```

Reglas propias del canal de cajeros (tope y múltiplo de billete):

```bash
curl -X POST http://localhost:8080/canal/cajero/retiro \
     -H "Authorization: Bearer $TC" -H "Content-Type: application/json" \
     -d '{"cuentaId":146,"monto":1250,"terminal":"ATM-042","referencia":"X1"}'
# 422: el cajero solo entrega múltiplos de 1000
```

### 6.4 Parte 3 — microservicios, saga y resiliencia

```bash
# Abrir una cuenta (verifica el titular en ms-clientes)
curl -X POST http://localhost:8080/api/cuentas \
     -H "Authorization: Bearer $TW" -H "Content-Type: application/json" \
     -d '{"clienteId":1,"tipoCuenta":"AHORRO","montoInicial":25000.00}'

# Iniciar un pago: responde 202 y PENDIENTE
curl -X POST http://localhost:8080/api/pagos \
     -H "Authorization: Bearer $TW" -H "Content-Type: application/json" \
     -d '{"cuentaId":146,"tipo":"RETIRO","monto":2000,"referencia":"P1","canal":"WEB"}'

# Un par de segundos después ya está CONFIRMADA o COMPENSADA
curl -H "Authorization: Bearer $TW" "http://localhost:8080/api/pagos?cuentaId=146&limite=2"

# ms-notificaciones, a quien nadie llamó, ya generó el aviso
curl -H "Authorization: Bearer $TW" "http://localhost:8080/api/notificaciones?limite=3"
```

Provocar la apertura del cortocircuito:

```bash
# Inducir 4 s de latencia en ms-cuentas (el límite del TimeLimiter es 3 s)
curl -X POST "http://localhost:8080/api/simulacion/latencia?milisegundos=4000" \
     -H "Authorization: Bearer $TW"

# Varios pagos seguidos y después:
curl http://localhost:8082/actuator/circuitbreakers | python3 -m json.tool

# Volver a la normalidad
curl -X POST "http://localhost:8080/api/simulacion/latencia?milisegundos=0" \
     -H "Authorization: Bearer $TW"
```

### 6.5 Escalabilidad horizontal

```bash
docker compose -f docker-compose.yml -f docker-compose.escalado.yml up -d \
    --scale ms-cuentas=3 --scale ms-pagos=3 --scale ms-clientes=3
```

Espere unos 45 segundos a que las réplicas se registren en Eureka y compruebe
que el gateway reparte:

```bash
for i in $(seq 1 20); do
  curl -s -H "Authorization: Bearer $TW" "http://localhost:8080/api/pagos?limite=1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["instancia"])'
done | sort | uniq -c
```

---

## 7. Ejecutar las pruebas

```bash
mvn test
```

**73 pruebas**, todas en verde:

| Módulo | Pruebas | Qué verifican |
|---|---|---|
| `ms-clientes` | 18 | El cálculo del segmento desde eventos y la idempotencia del consumo |
| `ms-cuentas` | 23 | Aplicación de pagos sobre el saldo y el ciclo de vida de una cuenta |
| `ms-pagos` | 17 | Los cinco patrones de resiliencia y el cierre de la saga |
| `ms-notificaciones` | 8 | La bandeja acotada y el aviso que corresponde a cada desenlace |
| `bff-movil` | 7 | La adaptación que hace el canal: abreviar, redondear, acortar |

Las de `ms-clientes` y `ms-cuentas` corren contra una base real (H2 en modo
PostgreSQL) usando **el mismo `esquema.sql` que producción**, no una copia: una
copia se desincroniza en silencio y las pruebas acaban validando una estructura
que ya no existe.

Las de resiliencia levantan un **servidor HTTP de verdad** al que se le indica
cómo portarse. Un doble de prueba que lanzara excepciones a voluntad
verificaría la configuración de Resilience4j sin pasar nunca por la red que esa
configuración protege.

---

## 8. Regenerar la evidencia

```bash
./scripts/evidencia-batch.sh           # consola/01-migracion-batch.txt
./scripts/evidencia-canales.sh         # consola/02-canales-bff.txt
./scripts/evidencia-microservicios.sh  # consola/03-microservicios.txt
./scripts/evidencia-resiliencia.sh     # consola/04-resiliencia.txt
./scripts/evidencia-escalado.sh        # consola/05-escalado-horizontal.txt
```

El orden importa: la evidencia de canales y microservicios necesita que la
migración haya corrido antes.

---

## 9. Si algo no funciona

| Síntoma | Causa más probable | Qué hacer |
|---|---|---|
| 503 al llamar por el gateway | El gateway aún no refrescó su copia del registro de Eureka | Esperar 10 s y reintentar; `./scripts/levantar.sh` ya lo comprueba |
| 401 con un token recién pedido | El token es de otro canal y no tiene el alcance | Pedirlo con el canal correcto |
| 403 en vez de 401 | El token es válido pero al canal le falta ese alcance | Es el comportamiento correcto: ver la tabla de la sección 4 |
| `/api/clientes/1` devuelve 404 tras un reinicio | Los datos los pone la migración, no el arranque | `./scripts/migrar.sh` |
| El batch responde 500 | Normalmente, un job lanzado fuera de orden | Usar `/migracion-completa` |
| Un puerto ocupado | Otro proyecto usa el mismo | Detenerlo o cambiar el mapeo en `docker-compose.yml` |

Para ver qué está pasando dentro:

```bash
docker compose ps
docker compose logs -f ms-pagos
curl http://localhost:8082/actuator/health | python3 -m json.tool
```
