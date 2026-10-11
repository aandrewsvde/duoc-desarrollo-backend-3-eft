#!/usr/bin/env bash
#
# Evidencia del patron BFF: los tres canales sobre los mismos datos.
#
# Lo que se quiere demostrar no es que cada canal funcione por separado, sino
# que los tres piden lo MISMO al mismo nucleo y devuelven cosas distintas. Por
# eso todas las consultas usan el mismo titular.
set -uo pipefail
cd "$(dirname "$0")/.."
SALIDA="evidencia/consola"; mkdir -p "$SALIDA"
exec > >(tee "$SALIDA/02-canales-bff.txt") 2>&1

GATEWAY="http://localhost:8080"
# Titular con mas cuentas de los migrados. La comparacion entre canales se
# aprecia mejor cuanto mas tiene que mostrar el panel: con una sola cuenta, el
# encabezado fijo de cada respuesta pesa mas que los datos y la diferencia
# parece menor de lo que es.
CLIENTE_DEMO="${CLIENTE_DEMO:-6}"

# El gateway necesita tener su copia del registro al dia antes de empezar,
# o las primeras secciones fallarian con 503 sin que nada este roto.
scripts/esperar-gateway.sh

titulo() { printf '\n==================================================================\n%s\n==================================================================\n' "$1"; }
json()   { python3 -m json.tool 2>/dev/null || cat; }

echo "Banco XYZ - Evidencia del patron Backend for Frontend"
echo "Generado: $(date '+%Y-%m-%d %H:%M:%S')"

TOKEN_WEB=$(scripts/obtener-token.sh canal-web)
TOKEN_MOVIL=$(scripts/obtener-token.sh canal-movil)
TOKEN_CAJERO=$(scripts/obtener-token.sh canal-cajero)

titulo "1. Un cliente OAuth 2.0 por canal"
echo "Cada BFF tiene credenciales propias y un conjunto de alcances distinto,"
echo "elegido por lo que ese canal puede legitimamente hacer. La consecuencia"
echo "practica es que comprometer un canal no abre los otros dos."
echo
for canal in canal-web canal-movil canal-cajero; do
    echo "--- ${canal} ---"
    scripts/lib/ver-token.py "$(scripts/obtener-token.sh $canal)" | sed -n '/scope/,/]/p'
done
echo
echo "   El movil NO tiene pagos.escribir: la aplicacion muestra saldos, y toda"
echo "   operacion que mueva dinero pasa por otro canal."
echo "   El cajero NO tiene clientes.leer: es un equipo en la via publica y no"
echo "   tiene por que poder consultar el padron de titulares."

titulo "2. El gateway exige credenciales antes de dejar entrar"
for ruta in canal/web/panel/${CLIENTE_DEMO} canal/movil/resumen/${CLIENTE_DEMO}; do
    printf '   %-34s sin token -> %s\n' "/${ruta}" \
        "$(curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY}/${ruta}")"
done
echo "   El trafico sin credenciales no llega siquiera a la red interna."

titulo "3. Canal WEB: la vista completa"
echo "Una pantalla de escritorio tiene espacio para mostrarlo todo y la conexion"
echo "rara vez es el cuello de botella, de modo que este canal optimiza por"
echo "NUMERO DE VIAJES: agrupa en una respuesta lo que de otro modo serian"
echo "varias llamadas del navegador."
echo
curl -sS -H "Authorization: Bearer $TOKEN_WEB" \
     "${GATEWAY}/canal/web/panel/${CLIENTE_DEMO}" -o /tmp/eft-web.json \
     -w "   respuesta: %{http_code}, %{size_download} bytes\n"
echo
python3 -c "
import json
d = json.load(open('/tmp/eft-web.json'))
print('   titular:   ', d['titular']['nombreCompleto'], '|', d['titular']['segmentoComercial'])
print('   patrimonio:', d['patrimonioConsolidado'])
print('   cuentas:   ', d['totalCuentas'])
c = d['cuentas'][0]
print()
print('   Primera cuenta, tal como la ve el navegador:')
print('     numeroCuenta     ', c['numeroCuenta'])
print('     tipoCuenta       ', c['tipoCuenta'])
print('     saldoDisponible  ', c['saldoDisponible'])
print('     totalAbonos      ', c['totalAbonos'])
print('     totalCargos      ', c['totalCargos'])
print('     movimientos      ', len(c['movimientosRecientes']), 'con fecha, tipo, descripcion y monto con signo')
"

titulo "4. Canal MOVIL: lo minimo que sirve"
echo "El objetivo opuesto. En una red movil cada byte cuesta bateria y tiempo:"
echo "nombres de campo cortos, titular abreviado, importes a entero, tipos de"
echo "una letra y tres movimientos por cuenta. Mismos datos, otra forma."
echo
curl -sS -H "Authorization: Bearer $TOKEN_MOVIL" \
     "${GATEWAY}/canal/movil/resumen/${CLIENTE_DEMO}" -o /tmp/eft-movil.json \
     -w "   respuesta: %{http_code}, %{size_download} bytes\n"
echo
python3 -c "
import json
d = json.load(open('/tmp/eft-movil.json'))
print('   Respuesta completa menos las cuentas:')
print('    ', json.dumps({k: v for k, v in d.items() if k != 'ctas'}, ensure_ascii=False))
print()
print('   Primera cuenta, tal como la ve el telefono:')
print('    ', json.dumps(d['ctas'][0], ensure_ascii=False))
"

titulo "5. La comparacion, que es el punto del patron"
python3 -c "
import os
w = os.path.getsize('/tmp/eft-web.json')
m = os.path.getsize('/tmp/eft-movil.json')
print('   canal web:   {:>6} bytes'.format(w))
print('   canal movil: {:>6} bytes   ({:.1f}% del web)'.format(m, 100 * m / w))
print()
print('   Mismo titular, mismo nucleo, misma informacion de fondo. Un unico')
print('   backend no puede optimizar a la vez para los dos: o manda de mas al')
print('   telefono o deja al navegador pidiendo en varias vueltas.')
"

titulo "6. Canal CAJERO: el mas restringido, y a proposito"
echo "Un cajero es un equipo fisico en la via publica. Si alguien se lleva el"
echo "terminal, se lleva sus credenciales: por eso este canal no devuelve el"
echo "nombre del titular, no muestra historial y su token dura cinco minutos."
echo
echo "\$ GET ${GATEWAY}/canal/cajero/saldo/146"
curl -sS -H "Authorization: Bearer $TOKEN_CAJERO" "${GATEWAY}/canal/cajero/saldo/146" | json
echo
echo "   Cuatro campos y ninguno identifica al titular."

titulo "7. Reglas propias del canal, que no existen en el nucleo"
echo "Un tope por operacion y multiplos de billete. No viven en ms-pagos porque"
echo "el mismo titular puede retirar mas desde una sucursal: son limitaciones"
echo "del terminal, no del dinero. Se comprueban ANTES de llamar al nucleo."
echo
echo "\$ POST /canal/cajero/retiro  monto 1250 (no es multiplo de 1000)"
curl -sS -X POST "${GATEWAY}/canal/cajero/retiro" -H "Authorization: Bearer $TOKEN_CAJERO" \
     -H "Content-Type: application/json" \
     -d '{"cuentaId":146,"monto":1250,"terminal":"ATM-042","referencia":"EV-1"}' | json
echo
echo "\$ POST /canal/cajero/retiro  monto 500000 (supera el tope del canal)"
curl -sS -X POST "${GATEWAY}/canal/cajero/retiro" -H "Authorization: Bearer $TOKEN_CAJERO" \
     -H "Content-Type: application/json" \
     -d '{"cuentaId":146,"monto":500000,"terminal":"ATM-042","referencia":"EV-2"}' | json

titulo "8. Autorizacion por canal, no solo autenticacion"
echo "El token del movil es valido y esta bien firmado, pero no alcanza para"
echo "mover dinero. Autenticar es saber quien eres; autorizar es saber que"
echo "puedes hacer, y aqui eso depende del canal."
echo
printf '   movil intentando crear un pago  -> %s\n' \
    "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${GATEWAY}/api/pagos" \
        -H "Authorization: Bearer $TOKEN_MOVIL" -H "Content-Type: application/json" \
        -d '{"cuentaId":146,"tipo":"RETIRO","monto":1000,"referencia":"NO-DEBE","canal":"MOVIL"}')"
printf '   cajero leyendo un cliente       -> %s\n' \
    "$(curl -sS -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $TOKEN_CAJERO" "${GATEWAY}/api/clientes/1")"
printf '   web leyendo el mismo cliente    -> %s\n' \
    "$(curl -sS -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $TOKEN_WEB" "${GATEWAY}/api/clientes/1")"

titulo "Fin de la evidencia de canales"
