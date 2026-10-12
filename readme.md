# Banco XYZ — Modernización del core bancario

**Evaluación Final Transversal · Desarrollo Backend III (PBY2203) · Semana 9**
Agustín Andrews V.

Migración de un core bancario COBOL/mainframe a una arquitectura de
microservicios: procesos batch reescritos en Spring Batch, un Backend for
Frontend por canal y microservicios resilientes con Spring Cloud, Resilience4j
y Kafka, todo orquestado con Docker Compose.

---

## Índice

1. [El problema](#1-el-problema)
2. [Los cinco procesos de la migración](#2-los-cinco-procesos-de-la-migración)
3. [Arquitectura](#3-arquitectura)
4. [Parte 1 — Migración batch](#4-parte-1--migración-batch)
5. [Parte 2 — Backend for Frontend](#5-parte-2--backend-for-frontend)
6. [Parte 3 — Microservicios resilientes](#6-parte-3--microservicios-resilientes)
7. [Docker y escalabilidad horizontal](#7-docker-y-escalabilidad-horizontal)
8. [Estructura del código](#8-estructura-del-código)
9. [Los datos](#9-los-datos)
10. [Pruebas](#10-pruebas)
11. [Evidencia de ejecución](#11-evidencia-de-ejecución)
12. [Decisiones y sus porqués](#12-decisiones-y-sus-porqués)
13. [Qué falta](#13-qué-falta)

> **Repositorio:** <https://github.com/aandrewsvde/duoc-desarrollo-backend-3-eft>

> **Cómo ejecutarlo:** [`instrucciones.md`](instrucciones.md)

> **Cómo desplegarlo en AWS:** [`despliegue.md`](despliegue.md)

> **Informe técnico:** `informe/Informe_tecnico_EFT_Agustin_Andrews.pdf`

---

## 1. El problema

El Banco XYZ opera desde hace más de treinta años sobre COBOL y scripts de
shell en un mainframe. Ese sistema funciona, y ese es justamente el punto de
partida que hace interesante el caso: no se migra porque esté roto, sino porque
ya no se puede cambiar a la velocidad que el negocio necesita.

Tres limitaciones concretas:

- **Escalabilidad.** El mainframe escala comprando más mainframe. No hay forma
  de dar más capacidad solo al módulo de pagos en un día de alta demanda.
- **Costo de mantenimiento.** Cada cambio toca un sistema único donde todo está
  acoplado, de modo que todo cambio es caro y arriesgado.
- **Integración.** Los frontends —web, móvil, cajeros— cuelgan de un backend
  monolítico que les sirve a los tres lo mismo, y sus desarrollos quedan presos
  de los tiempos de ese backend.

Lo que este proyecto entrega es el sistema que lo reemplaza, funcionando de
punta a punta.

---

## 2. Los cinco procesos de la migración

El caso exige identificar los procesos críticos de la migración. Son estos
cinco, y cada uno está implementado:

| # | Proceso | Dónde vive | Evidencia |
|---|---|---|---|
| 1 | **Migración de procesos batch a Spring Batch** | `batch/batch-financiero` | `evidencia/consola/01-migracion-batch.txt` |
| 2 | **División del monolito en microservicios** | `microservicios/ms-{clientes,cuentas,pagos}` | `evidencia/consola/03-microservicios.txt` |
| 3 | **Patrón Backend for Frontend** | `bff/bff-{web,movil,cajero}` | `evidencia/consola/02-canales-bff.txt` |
| 4 | **Seguridad distribuida con Spring Cloud Security** | `plataforma/authorization-server` + cada servicio como *resource server* | `evidencia/consola/02-canales-bff.txt`, secciones 1, 2 y 8 |
| 5 | **Mensajería asíncrona con Kafka** | `shared/eventos-contrato` + productores y consumidores | `evidencia/consola/03-microservicios.txt`, secciones 4 a 7 |

No están en ese orden por casualidad. El batch va primero porque sin datos
migrados no hay nada que servir; los microservicios antes que los BFF porque un
BFF sin núcleo detrás no adapta nada; y la seguridad y la mensajería atraviesan
a todos los demás en vez de ser una etapa aparte.

---

## 3. Arquitectura

```
                    ┌───────────────────┐  ┌───────────────────┐
                    │ config-server     │  │ eureka-server     │
                    │ :8888             │  │ :8761             │
                    └───────────────────┘  └───────────────────┘
                              configuración y descubrimiento

   navegador ─┐
   móvil ─────┼──▶ api-gateway :8080 ──┬──▶ bff-web     :8091 ──┐
   cajero ────┘    (valida el token)   ├──▶ bff-movil   :8092 ──┤
                                       └──▶ bff-cajero  :8093 ──┤
                                                                 │
          authorization-server :9000                             │
          (emite todos los tokens)                               ▼
                                              ┌──────────────────────────────┐
                                              │ ms-clientes       :8084      │
                                              │ ms-cuentas        :8081      │
                                              │ ms-pagos          :8082      │
                                              │ ms-notificaciones :8083      │
                                              └──────────────────────────────┘
                                                  │            │
   batch-financiero :8085 ───migración───────────▶│            │
   (lee los CSV del legado)                       ▼            ▼
                                            PostgreSQL      Kafka (KRaft)
                                            4 bases         4 tópicos
```

### Llamadas síncronas (las únicas dos)

```
ms-pagos   ──Resilience4j──▶ ms-cuentas     verificar la cuenta antes de publicar
ms-cuentas ──Resilience4j──▶ ms-clientes    verificar el titular antes de abrir
```

### Comunicación por eventos

```
ms-pagos ──banco.pagos.registrados──▶ ms-cuentas
                                           │
                 ┌─────────────────────────┼─────────────────────────┐
                 ▼                         ▼                         ▼
          ms-pagos                  ms-clientes            ms-notificaciones
      confirma o compensa      recalcula el segmento        genera el aviso

   banco.cuentas.saldo-actualizado · banco.pagos.rechazados · banco.seguridad.alertas
```

Los tres consumen el **mismo tópico con grupos distintos**: Kafka entrega una
copia a cada grupo. Si compartieran grupo, cada evento llegaría a uno solo y
dos de los tres quedarían desactualizados sin que nada avisara.

### Qué hace cada servicio

| Servicio | Responsabilidad | Por qué está separado |
|---|---|---|
| `config-server` | Configuración de los doce servicios | Cambiar el umbral de un cortocircuito no debe exigir reconstruir una imagen |
| `eureka-server` | Descubrimiento | Ningún servicio conoce la dirección de otro; pregunta por su nombre |
| `authorization-server` | Emite todos los tokens | El único que conoce credenciales; agregar un microservicio no obliga a reimplementar autenticación |
| `api-gateway` | Puerta única | El exterior ve una dirección y la topología interna puede cambiar sin que nadie se entere |
| `ms-clientes` | Identidad y perfil del titular | Los datos más sensibles, en el servicio más aislado: no llama a nadie |
| `ms-cuentas` | Saldo y ciclo de vida de la cuenta | Dueño exclusivo del saldo; nadie más escribe en su tabla |
| `ms-pagos` | Pagos, transferencias y depósitos | Origen de la saga |
| `ms-notificaciones` | Avisos al titular | Consumidor puro: demuestra que un servicio nuevo reacciona sin que el productor sepa que existe |
| `bff-web` / `bff-movil` / `bff-cajero` | Un backend por canal | Cada uno optimiza para su pantalla |
| `batch-financiero` | Migración del legado | Arranca, migra y espera; no es un servicio permanente |

---

## 4. Parte 1 — Migración batch

Tres jobs de Spring Batch, uno por cada proceso legado:

| Job | Archivo de entrada | Qué produce |
|---|---|---|
| `calculoInteresesMensualesJob` | `intereses_trimestrales.csv` | Intereses por cuenta; carga clientes y cuentas |
| `estadosCuentaAnualesJob` | `estados_financieros_anuales.csv` | Estados de cuenta; carga los movimientos |
| `reporteTransaccionesDiariasJob` | `movimientos_financieros_diarios.csv` | Resumen diario y anomalías; carga los pagos históricos |

### Resultados medidos

| Job | Leídos | Escritos | Omitidos | Migrados al destino |
|---|---|---|---|---|
| Intereses | 829 | 397 | 603 | 7 clientes + 50 cuentas |
| Estados anuales | 1.000 | 604 | 396 | 604 movimientos |
| Transacciones diarias | 1.000 | 392 | 608 | 392 pagos |

Que se omita más de la mitad **no es un fallo**: es exactamente lo que el
enunciado describe cuando dice que los archivos traen errores intencionales. Lo
que sí sería un fallo es que el proceso se detuviera en la primera fila mala, o
que las dejara entrar sin avisar. Cada omisión queda anotada con su motivo en
la tabla `registro_rechazado` y en un CSV de rechazos.

### Manejo de excepciones y reintentos

- **`PoliticaOmisionPersonalizada`** clasifica los errores en tres familias:
  defecto del dato (se omite y se anota), fallo de infraestructura (se
  reintenta) y desconocido (se detiene el job). La distinción importa: omitir
  un fallo de base de datos haría pasar por «dato malo» lo que es una caída.
- **`PoliticaReintentoPersonalizada`** reintenta solo lo transitorio, con
  espera creciente. Reintentar tres veces de inmediato contra una base saturada
  solo agrega carga a lo que ya está en problemas.
- **`CalidadDatosDecider`** deriva el flujo a una rama de alerta si la
  proporción de registros válidos cae por debajo del 30 %. Es lo que impide que
  un archivo corrupto cargue basura en las bases operativas.

### Paralelismo

El paso de carga de cada job está **particionado**: el archivo se divide en
rangos y cada uno se procesa en un hilo propio.

```
cargaTransaccionesWorkerStep:particion0   125 filas
cargaTransaccionesWorkerStep:particion1   125 filas
...                                       (8 particiones × 125 = 1.000)
```

`gridSize` y `chunkSize` se pasan como parámetros (`?gridSize=8&chunkSize=250`),
de modo que el escalado del batch se ajusta sin tocar código.

### La migración es idempotente

Relanzarla deja exactamente el mismo resultado: 7 clientes, 50 cuentas, 604
movimientos, 392 pagos antes y después. Un proceso de migración que acumulara
sobre lo anterior dejaría registros duplicados al reintentar tras un fallo.

---

## 5. Parte 2 — Backend for Frontend

Tres backends independientes, uno por canal, **sobre los mismos datos del mismo
núcleo**. Esa es la demostración del patrón: lo que cambia es la forma.

### La comparación, medida

Mismo titular (John Doe, 11 cuentas), misma información de fondo:

| Canal | Tamaño | Relación |
|---|---|---|
| Web | 7.735 bytes | 100 % |
| Móvil | 983 bytes | **12,7 %** |

El canal web entrega la vista completa porque una pantalla de escritorio tiene
espacio y la conexión rara vez es el cuello de botella: optimiza por **número
de viajes**, agrupando en una respuesta lo que serían varias llamadas del
navegador. El móvil hace lo opuesto —nombres de campo cortos, titular
abreviado, importes a entero, tipos de una letra— porque en una red móvil cada
byte cuesta batería y tiempo.

Un único backend no puede optimizar a la vez para los dos: o manda de más al
teléfono o deja al navegador pidiendo en varias vueltas.

### Autenticación específica por canal

Cada BFF es un **cliente OAuth 2.0 distinto**, con credenciales propias y
alcances elegidos por lo que ese canal puede legítimamente hacer:

| Canal | `clientes.leer` | `cuentas.leer` | `cuentas.escribir` | `pagos.leer` | `pagos.escribir` | Vida del token |
|---|---|---|---|---|---|---|
| Web | ✅ | ✅ | ✅ | ✅ | ✅ | 15 min |
| Móvil | ✅ | ✅ | — | ✅ | — | 15 min |
| Cajero | — | ✅ | — | ✅ | ✅ | **5 min** |

Las ausencias son el punto:

- **El móvil no tiene `pagos.escribir`.** La aplicación muestra saldos; toda
  operación que mueva dinero pasa por otro canal. Un alcance que no se concede
  no se puede abusar, y un teléfono es el dispositivo más fácil de perder.
- **El cajero no tiene `clientes.leer`.** Es un equipo físico en la vía
  pública: si alguien se lleva el terminal, no se lleva el padrón de titulares.
- **El token del cajero dura cinco minutos** y no quince, porque el riesgo de
  que alguien acceda al terminal es mayor.

Comprobado en la evidencia: el móvil intentando crear un pago recibe **403**;
el cajero leyendo un cliente recibe **403**; la web, **200**.

### Reglas propias del canal

El canal de cajeros impone un tope por operación (200.000) y múltiplos de
billete (1.000), **comprobados antes de llamar al núcleo**. No viven en
`ms-pagos` porque el mismo titular puede retirar más desde una sucursal: son
limitaciones del terminal, no del dinero.

---

## 6. Parte 3 — Microservicios resilientes

### Los tres microservicios del caso

| Microservicio | Qué administra | Operaciones |
|---|---|---|
| `ms-clientes` | Información personal y perfiles | Alta, consulta, actualización, baja lógica, segmento comercial |
| `ms-cuentas` | Apertura, cierre y mantenimiento | Abrir, bloquear, desbloquear, cerrar, bitácora, saldo y movimientos |
| `ms-pagos` | Pagos, transferencias y depósitos | Crear, consultar, resolver la saga |

### Saga coreografiada

No hay orquestador: cada servicio reacciona a los eventos que le interesan.

```
1. ms-pagos registra el pago como PENDIENTE y publica el hecho
2. ms-cuentas aplica el movimiento, o lo rechaza, y publica el desenlace
3. ms-pagos lo marca CONFIRMADO o COMPENSADO
   ms-clientes recalcula el segmento comercial
   ms-notificaciones genera el aviso
```

No hay transacción distribuida ni bloqueo de dos fases: cada servicio confirma
localmente y el acuerdo se alcanza por los eventos. El precio es que existe una
ventana en la que el sistema está en un estado intermedio, y por eso el estado
`PENDIENTE` es **visible en la API** en lugar de esconderse. La creación de un
pago responde **202 Accepted**, no 201: la operación fue aceptada, pero el
saldo todavía no cambió. Devolver 201 sugeriría que el dinero ya se movió.

### Idempotencia, en los dos extremos

Kafka entrega **al menos una vez**: tras un reequilibrio de particiones o un
reinicio, el mismo evento puede llegar dos veces. Una tabla `evento_procesado`
con clave primaria sobre `evento_id` lo impide, y se apoya en la restricción de
la base en lugar de en un `SELECT` previo, porque entre la consulta y la
inserción otro consumidor podría colarse — y esa ventana es justamente donde
aparecen los dobles cargos.

Del lado del canal, la referencia que envía el cliente es `UNIQUE`: un cajero
que reenvía la orden tras un corte de red no genera un segundo cargo.

### Resilience4j: los cinco patrones

Protegen las dos llamadas síncronas del sistema. Cada patrón cubre un modo de
fallo distinto y por sí solo ninguno basta:

| Patrón | Qué cubre | Configuración |
|---|---|---|
| **Retry** | El fallo pasajero: un paquete perdido, un reinicio de un segundo | 3 intentos, 300 ms, espera creciente ×2 |
| **TimeLimiter** | La dependencia que acepta la conexión pero no responde | 3 s |
| **Bulkhead** | Que la lentitud de un servicio consuma todos los hilos del que lo llama | 10 llamadas concurrentes |
| **RateLimiter** | Que este servicio tumbe al otro cuando vuelve | 50 por segundo |
| **CircuitBreaker** | Dejar de insistir contra algo que está caído | ventana de 10, mínimo 5 llamadas, 50 % de fallos, 10 s abierto |

Medido en la evidencia, con 4 s de latencia inducida sobre un límite de 3 s:

```
   Punto de partida                47 ms   verificado
   Con 4 s de latencia           3.038 ms  TimeoutException → respaldo
   Cuarto fallo seguido          3.112 ms  CLOSED → OPEN
   Con el circuito ABIERTO          27 ms  CallNotPermittedException
   Tras retirar la latencia         34 ms  OPEN → HALF_OPEN → CLOSED
```

### Dos políticas de respaldo opuestas, cada una justificada

Esto es lo que distingue aplicar los patrones de entenderlos:

- **`ms-pagos` ante un fallo de `ms-cuentas` degrada y acepta.** La
  verificación previa es una cortesía para fallar temprano, no la autoridad: la
  autoridad es `ms-cuentas` cuando aplica el evento, y seguirá rechazando lo que
  corresponda. Rechazar aquí convertiría la caída de una dependencia en una
  caída del servicio completo.
- **`ms-cuentas` ante un fallo de `ms-clientes` rechaza con 503.** Abrir una
  cuenta a nombre de alguien que no se pudo verificar crea un registro
  incorrecto, y deshacerlo después es trabajo manual sobre datos financieros.

Distinto riesgo, distinta decisión. Ambas comprobadas en
`evidencia/consola/04-resiliencia.txt`, sección 6.

### Qué NO cuenta como fallo

Que un recurso no exista. Un 404 significa que el otro servicio está sano y
respondió lo correcto. Si contara, un cliente pidiendo cuentas inexistentes
bastaría para abrir el cortocircuito y dejar sin servicio una dependencia que
funciona.

---

## 7. Docker y escalabilidad horizontal

### Imágenes

Un solo `docker/Dockerfile` multietapa con **una imagen final por servicio**.
La etapa de compilación se construye una vez y las doce finales la reutilizan;
con doce Dockerfile, el reactor Maven se ejecutaría doce veces sobre el mismo
código.

Otras decisiones: los `pom.xml` se copian antes que el código fuente (para que
la descarga de dependencias no se repita al cambiar una clase), la imagen de
ejecución es JRE y no JDK, y el proceso corre con un usuario sin privilegios.

### Orquestación

`docker-compose.yml` levanta **quince contenedores**. Lo más importante del
archivo es el orden de arranque: cada servicio espera a que sus dependencias
estén **sanas** (`condition: service_healthy`), no solo iniciadas. Un contenedor
de PostgreSQL acepta conexiones en el puerto segundos antes de poder atender
consultas, y un servicio que arranca en esa ventana muere al crear su esquema.

### Escalabilidad horizontal, comprobada

```bash
docker compose -f docker-compose.yml -f docker-compose.escalado.yml up -d \
    --scale ms-cuentas=3 --scale ms-pagos=3 --scale ms-clientes=3
```

Treinta consultas seguidas a la misma ruta por el gateway:

```
   --- ms-clientes ---                     --- ms-pagos ---
   ms-clientes:172.21.0.17:8084   10       ms-pagos:172.21.0.12:8082   10
   ms-clientes:172.21.0.20:8084   10       ms-pagos:172.21.0.19:8082   10
   ms-clientes:172.21.0.21:8084   10       ms-pagos:172.21.0.22:8082   10
   instancias distintas: 3                 instancias distintas: 3
```

Reparto exacto. Funciona porque el sistema ya estaba preparado: cada servicio se
registra en Eureka **por su dirección IP**, que es única por contenedor, y el
gateway y los BFF resuelven `lb://ms-cuentas` en vez de una dirección fija.

---

## 8. Estructura del código

```
EFT_S9_Agustin_Andrews/
├── readme.md  instrucciones.md  despliegue.md
├── pom.xml                           reactor Maven, 14 módulos
├── docker-compose.yml                orquestación de los 15 contenedores
├── docker-compose.escalado.yml       superposición para escalar réplicas
├── docker/Dockerfile                 multietapa, una imagen por servicio
│
├── shared/
│   ├── eventos-contrato/             los eventos de Kafka y los tópicos
│   └── canal-commons/                fontanería HTTP común a los tres BFF
│
├── plataforma/
│   ├── config-server/                + config-repo con los 10 archivos
│   ├── eureka-server/
│   ├── authorization-server/         OAuth 2.0 + OIDC
│   └── api-gateway/
│
├── batch/batch-financiero/           los tres jobs + la carga al destino
│
├── microservicios/
│   ├── ms-clientes/   ms-cuentas/   ms-pagos/   ms-notificaciones/
│
├── bff/
│   ├── bff-web/   bff-movil/   bff-cajero/
│
├── datos/legacy/                     los CSV del sistema legado
├── despliegue/aws/                   artefactos de infraestructura
├── evidencia/consola/                las cinco salidas
├── evidencia/capturas/              nueve capturas de los paneles
├── informe/                          el informe técnico en PDF
└── scripts/                          levantar, migrar, evidencia
```

**163 clases Java**, de las cuales 10 son de prueba. El código está **en
español** —paquetes, clases, métodos y variables— para que el dominio bancario
se lea en el mismo idioma que el enunciado. Las excepciones son los nombres que
imponen Spring, Docker o Kafka.

---

## 9. Los datos

Provienen de <https://github.com/KariVillagran/fin_legacy_data>, el repositorio
que entrega el enunciado. Traen defectos **intencionales**, que es justo lo que
el proceso batch debe saber manejar. Medidos sobre `semana_3`:

| Defecto | Dónde | Cuántos |
|---|---|---|
| Cuatro formatos de fecha conviviendo | los tres archivos | 1.000 de 1.000 |
| Montos vacíos | movimientos diarios | 168 |
| Montos negativos | movimientos diarios | 125 |
| Tipo de transacción inválido | movimientos diarios | 383 |
| Saldos vacíos | intereses | 204 |
| Edades fuera de rango o vacías | intereses | 217 |
| Tipo de cuenta como relleno (`-1`, `unknown`) | intereses | 325 |
| Descripciones vacías | estados anuales | 184 |

El archivo de intereses trae 1.000 filas para solo **50 cuentas**: unas 20
filas contradictorias por cuenta. Es la forma que toma aquí el problema de
«registros duplicados» que menciona el repositorio de origen, y se resuelve
quedándose con una fila por cuenta mediante un criterio determinista.

---

## 10. Pruebas

```bash
mvn test
```

**73 pruebas**, todas en verde:

| Módulo | Pruebas | Qué verifican |
|---|---|---|
| `ms-clientes` | 18 | Cálculo del segmento desde eventos; idempotencia del consumo |
| `ms-cuentas` | 23 | Aplicación de pagos sobre el saldo; ciclo de vida de la cuenta |
| `ms-pagos` | 17 | Los cinco patrones de resiliencia; cierre de la saga |
| `ms-notificaciones` | 8 | Bandeja acotada; aviso según el desenlace |
| `bff-movil` | 7 | La adaptación del canal: abreviar, redondear, acortar |

Dos decisiones sobre cómo están escritas:

- Las de `ms-clientes` y `ms-cuentas` corren contra una **base real** (H2 en
  modo PostgreSQL) y usan **el mismo `esquema.sql` que producción**, no una
  copia. Una copia se desincroniza en silencio y las pruebas acaban validando
  una estructura que ya no existe. Buena parte de lo que se quiere comprobar
  vive en la base: la clave primaria que da la idempotencia y la atomicidad de
  la transacción.
- Las de resiliencia levantan un **servidor HTTP de verdad** al que se le indica
  cómo portarse. Un doble de prueba que lanzara excepciones a voluntad
  verificaría la configuración de Resilience4j sin pasar nunca por la red que
  esa configuración protege.

---

## 11. Evidencia de ejecución

Toda en `evidencia/consola/`, generada con el sistema en marcha y reproducible
con los guiones de `scripts/`:

| Archivo | Qué demuestra |
|---|---|
| `01-migracion-batch.txt` | Los tres jobs, el particionado, los rechazos con su motivo, los informes y la idempotencia |
| `02-canales-bff.txt` | Los tres canales sobre los mismos datos, la comparación de tamaño y la autorización por canal |
| `03-microservicios.txt` | Descubrimiento, ciclo de vida de una cuenta, la saga en sus dos desenlaces, los tres consumidores y los mensajes reales de Kafka |
| `04-resiliencia.txt` | Los cinco patrones en acción, la apertura y recuperación del cortocircuito y las dos políticas de respaldo |
| `05-escalado-horizontal.txt` | Las imágenes, las réplicas registradas y el reparto medido del gateway |

A eso se suman **nueve capturas** en `evidencia/capturas/`, que muestran lo que
no cabe en una salida de texto: los paneles de Eureka y de Kafka, la
documentación viva de los servicios y el cortocircuito abierto visto desde el
propio actuator. Cada una está descrita en `evidencia/capturas/README.md`, con
los pasos para reproducirla.

---

## 12. Decisiones y sus porqués

### Una base de datos por servicio

`ms-clientes`, `ms-cuentas` y `ms-pagos` tienen bases **separadas**, no esquemas
dentro de una misma. Si compartieran esquema, un cambio de columna obligaría a
desplegar los dos a la vez y dejarían de ser independientes, que es lo único
que justifica partir el sistema.

La consecuencia se asume de frente: `cuenta.cliente_id` es un identificador,
**no una clave foránea**, porque la tabla de clientes vive en otra base. No hay
integridad referencial declarada, y ese es el precio de la independencia.

### El batch escribe en las tres bases, y eso no contradice lo anterior

Una migración es precisamente la operación que puebla esas bases **antes** de
que el sistema entre en servicio. Lo que no ocurre nunca es que el batch
escriba en ellas mientras los microservicios atienden tráfico.

Los tres jobs forman una unidad: el de intereses vacía las tablas de destino
antes de cargarlas, de modo que ejecutarlo solo borraría los movimientos que
cargó el de estados. Por eso existe `/api/batch/migracion-completa`.

### El emisor del token y las claves se configuran por separado

El token declara `iss: http://localhost:9000` porque esa es la dirección por la
que el **navegador** del usuario llega al servidor de autorización. Las claves
públicas, en cambio, se piden por la red interna de Docker
(`http://authorization-server:9000`). Si se usara una sola dirección, o la
redirección del login llevaría a un host inexistente, o los microservicios no
podrían resolver al emisor.

### `ms-notificaciones` no tiene base de datos

Una notificación es un aviso efímero: una vez entregada deja de importar.
Guardarla agregaría un contenedor y un esquema que mantener para algo que nadie
volverá a leer. Su razón de ser en el proyecto es demostrar que un servicio
nuevo puede reaccionar a los eventos sin que el productor sepa que existe.

### `canal-commons` existe y los BFF siguen siendo independientes

Lo compartido es solo **cómo** se habla con los microservicios —obtener un
token, resolver un nombre por Eureka, deserializar una respuesta—, que es
infraestructura idéntica en los tres. Lo que no se comparte, y es lo que define
al patrón, es **qué** pide cada canal y **qué forma** le da. La alternativa
sería triplicar el mismo cliente HTTP, y entonces una corrección de seguridad
habría que aplicarla tres veces y acordarse de las tres.

### El respaldo va en `@Retry`, no en `@CircuitBreaker`

Resilience4j aplica el reintento **por fuera** del cortocircuito. Un respaldo
declarado en el cortocircuito convertiría cada fallo en un resultado correcto
antes de que el reintento llegara a verlo: no habría reintentos en absoluto.

### Se desenvuelve el `CompletionException` antes de devolver el futuro

`CompletableFuture` envuelve en esa excepción todo lo que lanza su proveedor, y
Resilience4j se la entrega al clasificador **sin abrirla**. Como la lista de
excepciones que cuentan como fallo nombra las propias del dominio y no
`CompletionException`, cada caída se contabilizaría como éxito y el
cortocircuito no se abriría nunca: todo parecería funcionar mientras la
protección principal está inerte.

### Los esquemas no borran datos

Se ejecutan en **cada arranque** del servicio, de modo que un `DELETE` ahí
convertiría cualquier reinicio en una pérdida de datos silenciosa. Fue
exactamente lo que ocurrió al probar la recuperación de un servicio caído:
volvió sano y sin nada dentro. El reinicio de datos se hace donde corresponde,
borrando el volumen y volviendo a migrar.

### Cada instancia se registra en Eureka por su dirección IP

Con un nombre fijo por servicio, las tres réplicas de `ms-cuentas` se
registrarían con el mismo identificador y Eureka las tomaría por una sola: el
balanceo sería decorativo y nadie se enteraría, porque todo parece funcionar.

### `ActiveProcessorCount=2` y techo de CPU por contenedor

Cada JVM lee `/proc/cpuinfo`, ve todos los hilos de la máquina y dimensiona en
consecuencia sus hilos de recolección de basura, de compilación JIT y sus
pools. Doce procesos convencidos cada uno de tener la máquina entera suman
cientos de hilos ejecutables. Limitarlo no quita funcionalidad y hace que el
conjunto quepa en un portátil.

---

## 13. Qué falta

Dicho de frente, por orden de importancia:

1. **Los secretos están en el código.** Los de los clientes OAuth 2.0 están
   escritos en `ConfiguracionAuthorizationServer`. Deben estar en un almacén de
   secretos y leerse al arrancar.
2. **Las claves de firma se generan en cada arranque.** Al reiniciar el
   servidor de autorización, los tokens vigentes dejan de validar.
3. **No hay TLS.** El tráfico va en claro. En la nube lo resuelve un
   balanceador con certificado gestionado.
4. **Los clientes OAuth 2.0 están en memoria.** Darlos de alta exige
   reconstruir la imagen.
5. **El endpoint del batch no está autenticado.** Está protegido solo por no
   estar publicado.
6. **Un tópico por partición.** Con una sola partición, solo una réplica de
   cada grupo consume. Para que el escalado sirva también del lado de los
   eventos hacen falta tantas particiones como consumidores.
7. **El identificador de correlación no llega a los eventos.** El gateway marca
   cada petición, pero esa marca se pierde al publicar en Kafka.

Ninguno de estos puntos impide que el sistema funcione, y todos están
documentados en `despliegue.md` como condiciones previas a un despliegue real.
