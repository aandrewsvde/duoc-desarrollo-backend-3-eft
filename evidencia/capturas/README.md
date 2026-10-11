# Capturas de pantalla

Tomadas con los quince contenedores en marcha, el 11 de octubre de 2026.
Complementan las salidas de `../consola/`: aquí está lo que no se puede
demostrar en texto —los paneles de Eureka y de Kafka, la documentación viva de
cada servicio— y, al final, las dos respuestas que muestran el cortocircuito
abierto desde dentro del propio servicio.

| Archivo | Qué muestra |
|---|---|
| `01-eureka-registro.png` | Las **nueve aplicaciones** registradas y en estado `UP`: el gateway, el servidor de autorización, los tres BFF y los cuatro microservicios. Cada identificador de instancia lleva la IP del contenedor (`ms-pagos:172.21.0.12:8082`), que es lo que permite distinguir réplicas cuando se escala. En *General Info* se ve `num-of-cpus: 2`: el techo de procesadores que se impone a cada JVM. |
| `02-kafka-topicos.png` | Los **tres tópicos del dominio** con sus particiones y el recuento real de mensajes: `banco.pagos.registrados` (31), `banco.cuentas.saldo-actualizado` (29) y `banco.pagos.rechazados` (2). Los dos últimos son los dos desenlaces posibles de la saga; que el de rechazos tenga mensajes prueba que la compensación también se ejercitó. |
| `03-kafka-grupos-consumidores.png` | Los **cuatro grupos de consumidores**, todos `STABLE`. Que cada servicio tenga su propio grupo es lo que hace que todos reciban **todos** los eventos en lugar de repartírselos: con un único grupo compartido, `ms-cuentas` y `ms-notificaciones` se robarían los mensajes entre sí. |
| `04-kafka-eventos-saldo.png` | Mensajes reales de `banco.cuentas.saldo-actualizado`, con la referencia del pago como clave (`PG-04DD7CAD-1B7`) y el evento como contenido. La clave no es decorativa: garantiza que todos los eventos de un mismo pago caigan en la misma partición y se consuman en orden. |
| `05-swagger-ms-pagos.png` | Documentación viva de `ms-pagos`, origen de la saga. Los tres endpoints aparecen con el candado del esquema de seguridad, y la descripción deja explícito que crear un pago lo deja `PENDIENTE` y que el desenlace llega después por Kafka. |
| `06-swagger-ms-cuentas.png` | Documentación de `ms-cuentas`. Interesa por dos cosas: el grupo **Ciclo de vida** —apertura, bloqueo, cierre y bitácora— y el endpoint `/api/simulacion/latencia`, la palanca que induce la degradación controlada con la que se provoca el cortocircuito. |
| `07-swagger-bff-cajero.png` | La definición que publica `bff-cajero`. Su superficie es deliberadamente mínima: **dos operaciones**, retiro y saldo, y el saldo se entrega *sin datos del titular*. Puesto junto a los otros dos BFF, es la demostración más directa de por qué el patrón existe. |
| `08-cortocircuito-abierto.png` | `/actuator/circuitbreakers` de `ms-pagos` tras provocar cinco llamadas lentas con 4000 ms de retardo: el cortocircuito `cuentas` está en **`OPEN`** con `failureRate` 50 % sobre 10 llamadas en ventana, mientras el de `clientes` sigue `CLOSED`. Que uno esté abierto y el otro cerrado es el punto: el aislamiento es **por dependencia**, no por servicio. |
| `09-salud-ms-pagos.png` | El *health check* del mismo servicio en ese mismo momento. El estado global es **`UP` aunque `cuentas` esté en `CIRCUIT_OPEN`**: el servicio no se declara caído porque una dependencia lo esté, que es justamente lo que se busca con el patrón. La misma respuesta reúne los orígenes de configuración recibidos del config-server, la base PostgreSQL y el registro de Eureka con las nueve aplicaciones. |

## Cómo reproducirlas

```bash
./scripts/levantar.sh
```

Las direcciones están en `instrucciones.md`, sección 3, «Direcciones». En
resumen:

| Captura | Dirección |
|---|---|
| 01 | <http://localhost:8761> |
| 02 a 04 | <http://localhost:8090> → *Topics* / *Consumers* |
| 05 a 07 | <http://localhost:8082/swagger-ui.html>, `:8081`, `:8093` |
| 08 | <http://localhost:8082/actuator/circuitbreakers> |
| 09 | <http://localhost:8082/actuator/health> |

Las capturas 02 a 04 necesitan que haya pasado tráfico: basta con haber
ejecutado antes `scripts/evidencia-microservicios.sh`.

Para las capturas 08 y 09 hay que abrir el cortocircuito primero:

```bash
TOKEN=$(scripts/obtener-token.sh canal-web)

# 1. Se degrada ms-cuentas: responde, pero tarde.
curl -X POST "http://localhost:8080/api/simulacion/latencia?milisegundos=4000" \
     -H "Authorization: Bearer $TOKEN"

# 2. Cinco pagos seguidos. El limitador corta a los 3 s y cada corte
#    cuenta como fallo; al quinto se supera el umbral del 50 %.
for i in $(seq 1 5); do
  curl -s -o /dev/null -X POST http://localhost:8080/api/pagos \
       -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d "{\"cuentaId\":146,\"tipo\":\"DEPOSITO\",\"monto\":10.00,\"referencia\":\"CB-$i\",\"canal\":\"WEB\"}"
done
```

**Después hay que devolver el sistema a su estado normal**, o todo lo que se
ejecute a continuación seguirá viendo un servicio degradado:

```bash
curl -X POST "http://localhost:8080/api/simulacion/latencia?milisegundos=0" \
     -H "Authorization: Bearer $TOKEN"
```

El cortocircuito se cierra solo: pasa a `HALF_OPEN` a los 10 segundos y vuelve
a `CLOSED` en cuanto las tres llamadas de prueba tienen éxito. No hace falta
reiniciar nada.
