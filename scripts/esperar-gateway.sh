#!/usr/bin/env bash
#
# Espera a que el gateway pueda enrutar hacia todos los destinos.
#
# Que los contenedores esten sanos no basta: el gateway guarda una copia local
# del registro de Eureka y, hasta refrescarla, responde 503 para servicios que
# ya estan en pie. Un guion de evidencia lanzado en esa ventana falla sin que
# haya nada roto, y el 503 se parece lo suficiente a un fallo real como para
# hacer perder el tiempo a quien lo lea.
#
# Un 401 es la respuesta CORRECTA sin token: significa que la ruta existe y el
# gateway la atendio. Un 503 significa que todavia no conoce el destino.
set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
INTENTOS="${INTENTOS:-40}"

RUTAS=(
    api/clientes/1 api/cuentas/101 api/pagos api/notificaciones
    canal/web/panel/1 canal/movil/resumen/1 canal/cajero/saldo/101
)

for intento in $(seq 1 "$INTENTOS"); do
    pendientes=0
    for ruta in "${RUTAS[@]}"; do
        codigo=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
                 "${GATEWAY}/${ruta}" 2>/dev/null || echo 000)
        [ "$codigo" = "401" ] || pendientes=$((pendientes + 1))
    done
    [ "$pendientes" -eq 0 ] && exit 0
    sleep 5
done

echo "AVISO: el gateway no enruta hacia ${pendientes} destino(s) tras ${INTENTOS} intentos." >&2
exit 1
