#!/usr/bin/env bash
#
# Detiene la plataforma.
#
# Por defecto conserva el volumen de PostgreSQL. Con --limpiar lo elimina, que
# es lo que hay que hacer para volver al estado inicial: el script de creacion
# de bases solo se ejecuta cuando el volumen esta vacio, de modo que sin
# borrarlo un cambio en ese script no tendria ningun efecto.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--limpiar" ]; then
    echo "==> Deteniendo y eliminando volumenes"
    docker compose down -v
else
    echo "==> Deteniendo (se conservan los datos)"
    docker compose down
fi
