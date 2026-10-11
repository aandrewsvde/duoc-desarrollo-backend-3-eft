#!/usr/bin/env bash
#
# Ejecuta la migracion completa del sistema legado.
#
# Lanza los tres jobs en el orden correcto: intereses crea las cuentas, y tanto
# los movimientos anuales como las transacciones diarias las referencian.
# Lanzarlos al reves deja los dos ultimos sin nada que migrar.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Lanzando la migracion (tres jobs de Spring Batch)"
curl -sS -X POST http://localhost:8085/api/batch/migracion-completa --max-time 900 \
  | python3 scripts/lib/migracion.py

echo
echo "==> Datos migrados a los microservicios"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_clientes -t -c \
  "SELECT '   clientes:    '||count(*) FROM cliente"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_cuentas -t -c \
  "SELECT '   cuentas:     '||count(*) FROM cuenta;
   SELECT '   movimientos: '||count(*) FROM movimiento"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_pagos -t -c \
  "SELECT '   pagos:       '||count(*) FROM pago"
