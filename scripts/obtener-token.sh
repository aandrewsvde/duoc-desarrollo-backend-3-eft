#!/usr/bin/env bash
#
# Obtiene un token por el flujo client_credentials y lo imprime.
#
# Es el flujo de maquina: no hay usuario ni navegador, solo un cliente que se
# identifica con sus propias credenciales. Cada canal tiene el suyo, y por eso
# este script recibe cual usar.
#
# Uso:  scripts/obtener-token.sh [canal-web|canal-movil|canal-cajero|banco-servicios] ["alcances"]
set -euo pipefail
cd "$(dirname "$0")/.."

EMISOR="${EMISOR:-http://localhost:9000}"
CLIENTE="${1:-canal-web}"

# Secreto y alcances por canal. Estan aqui y no en una variable suelta para que
# el script sirva de documentacion de que puede hacer cada uno.
case "$CLIENTE" in
  canal-web)       SECRETO="canal-web-secreto-2026"
                   ALCANCES="clientes.leer cuentas.leer cuentas.escribir pagos.leer pagos.escribir" ;;
  canal-movil)     SECRETO="canal-movil-secreto-2026"
                   ALCANCES="clientes.leer cuentas.leer pagos.leer" ;;
  canal-cajero)    SECRETO="canal-cajero-secreto-2026"
                   ALCANCES="cuentas.leer pagos.leer pagos.escribir" ;;
  banco-servicios) SECRETO="banco-servicios-secreto-2026"
                   ALCANCES="clientes.leer cuentas.leer pagos.leer" ;;
  *) echo "Cliente desconocido: $CLIENTE" >&2; exit 1 ;;
esac

ALCANCES="${2:-$ALCANCES}"

curl -sS -X POST "${EMISOR}/oauth2/token" \
    -u "${CLIENTE}:${SECRETO}" \
    -d grant_type=client_credentials \
    -d "scope=${ALCANCES}" \
  | python3 scripts/lib/token.py
