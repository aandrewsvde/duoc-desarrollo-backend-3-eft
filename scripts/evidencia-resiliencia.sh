#!/usr/bin/env bash
#
# Evidencia de los patrones de Resilience4j con el sistema en marcha.
#
# Se provocan dos averias distintas porque demuestran cosas distintas: la
# DEGRADACION -el servicio responde, pero tarde- y la CAIDA total. La primera
# es el caso interesante, porque es la que un sistema sin limite de tiempo no
# detecta: todo parece funcionar mientras los hilos se agotan.
set -uo pipefail
cd "$(dirname "$0")/.."
SALIDA="evidencia/consola"; mkdir -p "$SALIDA"
exec > >(tee "$SALIDA/04-resiliencia.txt") 2>&1

GATEWAY="http://localhost:8080"
PAGOS="http://localhost:8082"
CUENTAS="http://localhost:8081"
CUENTA="${CUENTA_DEMO:-146}"

# El gateway necesita tener su copia del registro al dia antes de empezar,
# o las primeras secciones fallarian con 503 sin que nada este roto.
scripts/esperar-gateway.sh

titulo() { printf '\n==================================================================\n%s\n==================================================================\n' "$1"; }
estado() { curl -sS "${PAGOS}/actuator/circuitbreakers" | python3 scripts/lib/cortocircuito.py cuentas; }
latencia() { curl -sS -o /dev/null -X POST "${GATEWAY}/api/simulacion/latencia?milisegundos=$1" \
                  -H "Authorization: Bearer $TOKEN"; }

pago_cronometrado() {
    local referencia="$1"
    local inicio=$(date +%s%N)
    local cuerpo
    cuerpo=$(curl -sS -X POST "${GATEWAY}/api/pagos" -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"cuentaId\":${CUENTA},\"tipo\":\"DEPOSITO\",\"monto\":10.00,\"referencia\":\"${referencia}\",\"canal\":\"WEB\"}")
    local ms=$(( ($(date +%s%N) - inicio) / 1000000 ))
    echo "$cuerpo" | MILISEGUNDOS="$ms" python3 scripts/lib/pago-cronometrado.py
}

echo "Banco XYZ - Evidencia de resiliencia"
echo "Generado: $(date '+%Y-%m-%d %H:%M:%S')"

TOKEN=$(scripts/obtener-token.sh canal-web)

titulo "0. Configuracion vigente, entregada por el config-server"
curl -sS "http://localhost:8888/ms-pagos/default" | python3 -c '
import json, sys
vistas = {}
for origen in json.load(sys.stdin)["propertySources"]:
    for clave, valor in origen["source"].items():
        if clave.startswith("resilience4j") and clave not in vistas:
            vistas[clave] = valor
for clave in sorted(vistas):
    print("   {} = {}".format(clave, vistas[clave]))'

titulo "1. Punto de partida: ms-cuentas sano"
latencia 0; sleep 1
estado
pago_cronometrado "RES-SANO-$RANDOM"
echo "   La cuenta se verifica contra ms-cuentas y la respuesta es inmediata."

titulo "2. DEGRADACION: ms-cuentas responde, pero en 4 segundos"
echo "El limite del TimeLimiter es de 3 s. Sin el, la peticion esperaria los 4 s"
echo "completos y, con suficiente trafico, los hilos del servicio se agotarian."
echo
latencia 4000; sleep 1
pago_cronometrado "RES-LENTO-$RANDOM"
echo
echo "   Corto antes de los 4 s y el respaldo dejo seguir el pago. No invento un"
echo "   saldo: lo marco como no verificado, y quien decide de verdad es"
echo "   ms-cuentas cuando aplique el evento."

titulo "3. El cortocircuito se abre con los fallos sostenidos"
for i in 1 2 3 4; do
    pago_cronometrado "RES-ABRIR-${i}-$RANDOM"
    estado
done

titulo "4. Con el cortocircuito ABIERTO la llamada ni siquiera viaja"
pago_cronometrado "RES-ABIERTO-$RANDOM"
estado
echo
echo "   Se responde con el respaldo en milisegundos en vez de esperar un"
echo "   timeout que ya se sabe que va a ocurrir. Eso es lo que impide que la"
echo "   lentitud de una dependencia se propague a quien la consume."
echo
echo "   Nota: el cortocircuito pasa solo a SEMIABIERTO cada 10 s para probar si"
echo "   el otro servicio se recupero. Si una medicion sale lenta, es una de"
echo "   esas llamadas de prueba, y al fallar vuelve a abrirse."

titulo "5. Recuperacion automatica: ABIERTO -> SEMIABIERTO -> CERRADO"
latencia 0
echo "   latencia retirada; esperando la ventana de 10 s..."
sleep 12
estado
echo
echo "   Llamadas de prueba en estado SEMIABIERTO:"
for i in 1 2 3 4; do
    pago_cronometrado "RES-RECUP-${i}-$RANDOM"
    estado
done
echo
echo "   El cortocircuito se cerro solo. Nadie intervino."

titulo "6. Dos politicas de respaldo opuestas, cada una justificada"
echo "ms-pagos ante un fallo de ms-cuentas DEGRADA y acepta: el evento se"
echo "resolvera cuando el servicio vuelva."
echo "ms-cuentas ante un fallo de ms-clientes RECHAZA: abrir una cuenta a"
echo "nombre de alguien que no se pudo verificar crea un registro incorrecto"
echo "que luego hay que deshacer a mano."
echo
echo "   Se detiene ms-clientes para comprobarlo..."
docker compose stop ms-clientes >/dev/null 2>&1
sleep 3
echo
echo "   \$ POST /api/cuentas  con ms-clientes caido"
curl -sS -X POST "${GATEWAY}/api/cuentas" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"clienteId":1,"tipoCuenta":"AHORRO","montoInicial":1000.00}' \
     --max-time 30 | python3 -m json.tool
echo
echo "   \$ POST /api/pagos  con ms-clientes caido (no es su dependencia)"
pago_cronometrado "RES-CLIENTES-CAIDO-$RANDOM"
echo
curl -sS "${CUENTAS}/actuator/circuitbreakers" | python3 scripts/lib/cortocircuito.py clientes

titulo "7. Recuperacion de ms-clientes"
docker compose start ms-clientes >/dev/null 2>&1
echo "   ms-clientes levantado; esperando a que quede sano..."
for intento in $(seq 1 40); do
    SALUD=$(curl -sS "http://localhost:8084/actuator/health" 2>/dev/null \
            | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' 2>/dev/null || echo DOWN)
    [ "$SALUD" = "UP" ] && break
    sleep 3
done
echo "   estado de ms-clientes: ${SALUD}"
echo "   esperando a que vuelva a figurar en Eureka..."
for intento in $(seq 1 30); do
    curl -sS -H "Accept: application/json" "http://localhost:8761/eureka/apps" 2>/dev/null \
      | grep -q "MS-CLIENTES" && { echo "   registrado de nuevo"; break; }
    sleep 3
done
sleep 12
echo
echo "   \$ POST /api/cuentas  con ms-clientes restablecido"
curl -sS -X POST "${GATEWAY}/api/cuentas" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"clienteId":1,"tipoCuenta":"AHORRO","montoInicial":1000.00}' --max-time 30 \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("   cuenta {} abierta para {}".format(d.get("numeroCuenta"), d.get("titular"))) if "numeroCuenta" in d else print("   ", d)'

titulo "8. Metricas y bitacora del cortocircuito"
estado
curl -sS "${CUENTAS}/actuator/circuitbreakers" | python3 scripts/lib/cortocircuito.py clientes
echo
echo "   Ultimos eventos registrados:"
curl -sS "${PAGOS}/actuator/circuitbreakerevents?name=cuentas" | python3 -c '
import json, sys
for e in json.load(sys.stdin)["circuitBreakerEvents"][-14:]:
    detalle = e.get("stateTransition") or (e.get("errorMessage") or "")[:70]
    print("   {}  {:<24} {}".format(e["creationTime"][11:23], e["type"], detalle))'

titulo "Fin de la evidencia de resiliencia"
