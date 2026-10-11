#!/usr/bin/env bash
#
# Evidencia del criterio 6: Docker y escalabilidad horizontal.
#
# Levanta varias replicas de los tres microservicios de negocio y comprueba
# que el gateway reparte entre ellas. La comprobacion no es que los
# contenedores existan: es que respuestas sucesivas a la MISMA ruta las
# contesten instancias distintas.
set -uo pipefail
cd "$(dirname "$0")/.."
SALIDA="evidencia/consola"; mkdir -p "$SALIDA"
exec > >(tee "$SALIDA/05-escalado-horizontal.txt") 2>&1

GATEWAY="http://localhost:8080"
REPLICAS="${REPLICAS:-3}"
titulo() { printf '\n==================================================================\n%s\n==================================================================\n' "$1"; }

echo "Banco XYZ - Evidencia de escalado horizontal"
echo "Generado: $(date '+%Y-%m-%d %H:%M:%S')"

titulo "1. Imagenes construidas: una por servicio"
echo "Cada microservicio tiene su propia imagen con su propio jar. La de"
echo "bff-movil no contiene el codigo de ms-cuentas, que es la independencia"
echo "de despliegue que se busca."
echo
docker images --filter "reference=bancoxyz/*" \
    --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | sed 's/^/   /'
echo
echo "   El tamano que informa Docker cuenta las capas compartidas una vez por"
echo "   imagen. Las doce parten de la MISMA etapa base, de modo que en disco"
echo "   hay una base y doce jar, no doce bases. Es tambien lo que hace que"
echo "   desplegar un servicio descargue decenas de megas y no cientos."

titulo "2. Antes de escalar: una instancia de cada uno"
curl -sS -H "Accept: application/json" "http://localhost:8761/eureka/apps" \
  | python3 scripts/lib/registro-eureka.py

titulo "3. Se levantan ${REPLICAS} replicas de los tres microservicios"
echo "El archivo docker-compose.escalado.yml quita el nombre fijo de contenedor"
echo "y el puerto del anfitrion, que es lo unico que impedia tener mas de una"
echo "replica. El resto del sistema ya estaba preparado: los BFF y el gateway"
echo "resuelven lb://ms-cuentas, no una direccion."
echo
docker compose -f docker-compose.yml -f docker-compose.escalado.yml up -d \
    --scale ms-clientes="${REPLICAS}" \
    --scale ms-cuentas="${REPLICAS}" \
    --scale ms-pagos="${REPLICAS}" 2>&1 | tail -6

echo
echo "   Esperando a que las replicas nuevas se registren en Eureka..."
sleep 45
docker compose -f docker-compose.yml -f docker-compose.escalado.yml ps \
    --format 'table {{.Service}}\t{{.Status}}' | grep -E 'ms-clientes|ms-cuentas|ms-pagos|SERVICE' | sed 's/^/   /'

titulo "4. Eureka ve todas las instancias"
curl -sS -H "Accept: application/json" "http://localhost:8761/eureka/apps" \
  | python3 scripts/lib/registro-eureka.py

titulo "5. La prueba de verdad: el gateway reparte entre las replicas"
echo "Treinta consultas seguidas a la misma ruta. El campo 'instancia' de cada"
echo "respuesta delata cual contesto; si el balanceo fuera decorativo, saldria"
echo "siempre la misma."
echo
TOKEN=$(scripts/obtener-token.sh canal-web)
echo "   --- ms-clientes (GET /api/clientes) ---"
for i in $(seq 1 30); do
    curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/clientes?limite=1" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("instancia",""))' 2>/dev/null
done | python3 scripts/lib/instancias.py
echo
echo "   --- ms-pagos (GET /api/pagos) ---"
for i in $(seq 1 30); do
    curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/pagos?limite=1" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("instancia",""))' 2>/dev/null
done | python3 scripts/lib/instancias.py

titulo "6. El trabajo de Kafka tambien se reparte"
echo "Las replicas de ms-cuentas comparten grupo de consumidores, de modo que"
echo "Kafka les asigna particiones distintas del mismo topico. Es el otro lado"
echo "del escalado: no solo se reparten las peticiones HTTP, tambien los"
echo "eventos."
echo
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --group ms-cuentas 2>/dev/null \
    | sed 's/^/   /' | head -8
echo
echo "   Con un solo topico de una particion, solo una replica consume y las"
echo "   demas quedan en reserva: es el comportamiento correcto de Kafka, no"
echo "   un fallo. Para repartir de verdad hacen falta tantas particiones como"
echo "   consumidores, y eso se configura al crear el topico."

titulo "7. Vuelta al estado normal"
echo "Se vuelve al compose base, no al de escalado. La diferencia importa: el"
echo "archivo de escalado quita los puertos del anfitrion, de modo que quedarse"
echo "en el dejaria los microservicios accesibles solo por el gateway y los"
echo "demas guiones de evidencia -que consultan /actuator en el puerto directo-"
echo "fallarian sin causa aparente."
echo
docker compose up -d --remove-orphans 2>&1 | tail -4
echo
echo "   Esperando a que los servicios vuelvan a estar sanos..."
for intento in $(seq 1 40); do
    SANOS=$(docker compose ps --format '{{.Health}}' 2>/dev/null | grep -c healthy)
    [ "$SANOS" -ge 14 ] && break
    sleep 10
done
echo "   servicios sanos: ${SANOS}"

titulo "Fin de la evidencia de escalado"
