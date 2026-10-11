#!/usr/bin/env bash
#
# Evidencia de la Parte 3: microservicios, saga por eventos y resiliencia.
set -uo pipefail
cd "$(dirname "$0")/.."
SALIDA="evidencia/consola"; mkdir -p "$SALIDA"
exec > >(tee "$SALIDA/03-microservicios.txt") 2>&1

GATEWAY="http://localhost:8080"
# El gateway necesita tener su copia del registro al dia antes de empezar,
# o las primeras secciones fallarian con 503 sin que nada este roto.
scripts/esperar-gateway.sh

titulo() { printf '\n==================================================================\n%s\n==================================================================\n' "$1"; }
json()   { python3 -m json.tool 2>/dev/null || cat; }

echo "Banco XYZ - Evidencia de microservicios, eventos y resiliencia"
echo "Generado: $(date '+%Y-%m-%d %H:%M:%S')"

TOKEN=$(scripts/obtener-token.sh canal-web)
CUENTA="${CUENTA_DEMO:-146}"

titulo "1. Descubrimiento: ningun servicio conoce la direccion de otro"
curl -sS -H "Accept: application/json" "http://localhost:8761/eureka/apps" \
  | python3 scripts/lib/registro-eureka.py

titulo "2. Los tres microservicios del caso, cada uno dueno de su dominio"
for recurso in clientes/1 cuentas/${CUENTA} "pagos?limite=1"; do
    printf '   GET /api/%-22s -> %s\n' "$recurso" \
        "$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
           "${GATEWAY}/api/${recurso}")"
done
echo
echo "   ms-clientes es el unico dueno de la identidad; ms-cuentas, del saldo;"
echo "   ms-pagos, de las operaciones. Ninguno escribe en la base de otro."

titulo "3. Ciclo de vida de una cuenta (apertura, bloqueo, cierre)"
echo "\$ POST /api/cuentas  {cliente 1, AHORRO, deposito inicial 25000}"
NUEVA=$(curl -sS -X POST "${GATEWAY}/api/cuentas" -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"clienteId":1,"tipoCuenta":"AHORRO","montoInicial":25000.00}')
echo "$NUEVA" | json
NUM=$(echo "$NUEVA" | python3 -c 'import json,sys; print(json.load(sys.stdin)["numeroCuenta"])')
echo
echo "   El titular NO viene en la peticion: lo resuelve ms-cuentas contra"
echo "   ms-clientes. Aceptarlo del canal permitiria abrir una cuenta a nombre"
echo "   de cualquiera."
echo
echo "\$ PATCH /api/cuentas/${NUM}/estado  {CERRADA}  -- con saldo 25000"
curl -sS -X PATCH "${GATEWAY}/api/cuentas/${NUM}/estado" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"estado":"CERRADA","motivo":"Peticion del titular"}' | json
echo "   409 y no 500: es una regla de negocio, no una averia. Cerrar una"
echo "   cuenta con saldo dejaria dinero atrapado."
echo
echo "\$ PATCH /api/cuentas/${NUM}/estado  {BLOQUEADA}"
curl -sS -X PATCH "${GATEWAY}/api/cuentas/${NUM}/estado" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"estado":"BLOQUEADA","motivo":"Sospecha de fraude"}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("   estado:", d["estado"])'
echo
echo "\$ GET /api/cuentas/${NUM}/bitacora"
curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/cuentas/${NUM}/bitacora" | json

titulo "4. Saga coreografiada por Kafka: desenlace CONFIRMADO"
SALDO_ANTES=$(curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/cuentas/${CUENTA}" \
              | python3 -c 'import json,sys; print(json.load(sys.stdin)["saldo"])')
echo "   saldo previo de la cuenta ${CUENTA}: ${SALDO_ANTES}"
echo
echo "\$ POST /api/pagos  {RETIRO 2000}"
curl -sS -X POST "${GATEWAY}/api/pagos" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d "{\"cuentaId\":${CUENTA},\"tipo\":\"RETIRO\",\"monto\":2000.00,\"referencia\":\"EV-OK-$RANDOM\",\"canal\":\"WEB\"}" \
  | json
echo
echo "   Responde 202 y PENDIENTE, no 201: la operacion fue aceptada pero el"
echo "   saldo todavia no cambio. El desenlace llega por evento."
echo
echo "   ...esperando a que la saga se cierre..."
sleep 4
curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/pagos?cuentaId=${CUENTA}&limite=2" \
  | python3 scripts/lib/pagos.py

titulo "5. Saga coreografiada: desenlace COMPENSADO"
echo "\$ POST /api/pagos  {RETIRO 9999999}"
curl -sS -X POST "${GATEWAY}/api/pagos" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d "{\"cuentaId\":${CUENTA},\"tipo\":\"RETIRO\",\"monto\":9999999.00,\"referencia\":\"EV-NO-$RANDOM\",\"canal\":\"WEB\"}" \
  -o /dev/null -w "   respuesta: %{http_code}\n"
sleep 4
curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/pagos?cuentaId=${CUENTA}&limite=2" \
  | python3 scripts/lib/pagos.py
echo
echo "   El saldo no se toco en ningun momento: ms-cuentas rechazo antes de"
echo "   aplicarlo y ms-pagos compenso con el motivo."

titulo "6. Tres consumidores del mismo evento, sin que el productor lo sepa"
echo "ms-pagos, ms-clientes y ms-notificaciones escuchan el mismo topico de"
echo "saldo con grupos distintos. Kafka entrega una copia a CADA grupo."
echo
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --list 2>/dev/null | sed 's/^/   /'
echo
echo "   Avisos que genero ms-notificaciones:"
curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/notificaciones?limite=3" \
  | python3 scripts/lib/notificaciones.py
echo
echo "   Segmento comercial que recalculo ms-clientes al enterarse:"
curl -sS -H "Authorization: Bearer $TOKEN" "${GATEWAY}/api/clientes/1" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("   cliente {}: {} con saldo consolidado {}".format(d["id"], d["segmento"], d["saldoTotal"]))'

titulo "7. Mensajes reales en los topicos"
for topico in banco.pagos.registrados banco.cuentas.saldo-actualizado banco.pagos.rechazados; do
    echo "--- ${topico} ---"
    docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 --topic "$topico" \
        --from-beginning --max-messages 2 --timeout-ms 8000 2>/dev/null \
        | sed 's/^/   /' || echo "   (sin mensajes)"
    echo
done

titulo "8. Idempotencia: reenviar la misma orden no duplica el cargo"
REF="EV-IDEM-$RANDOM"
echo "\$ POST /api/pagos  dos veces con la referencia ${REF}"
for intento in 1 2; do
    curl -sS -X POST "${GATEWAY}/api/pagos" -H "Authorization: Bearer $TOKEN" \
         -H "Content-Type: application/json" \
         -d "{\"cuentaId\":${CUENTA},\"tipo\":\"DEPOSITO\",\"monto\":500.00,\"referencia\":\"${REF}\",\"canal\":\"WEB\"}" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print("   intento: id={} {}".format(d["pago"]["id"], d["siguientePaso"]))'
done
sleep 3
echo
echo "   Es la MISMA operacion: el segundo intento devuelve la original."

titulo "9. Resiliencia: estado inicial de los cortocircuitos"
curl -sS "http://localhost:8082/actuator/circuitbreakers" | python3 scripts/lib/cortocircuito.py cuentas
curl -sS "http://localhost:8081/actuator/circuitbreakers" | python3 scripts/lib/cortocircuito.py clientes

titulo "Fin de la evidencia de microservicios"
