#!/usr/bin/env bash
#
# Levanta la plataforma completa y espera a que este operativa.
#
# No basta con "docker compose up -d": ese comando vuelve en cuanto los
# contenedores arrancan, no cuando los servicios estan listos. Doce
# aplicaciones Spring con config-server, Eureka y Kafka por delante tardan
# bastante mas, y lanzar la migracion en ese intervalo produce errores que
# parecen fallos del sistema y solo son impaciencia.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Construyendo imagenes"
docker compose build

echo "==> Levantando contenedores"
docker compose up -d

echo "==> Esperando a que los servicios queden sanos"
ESPERA_MAXIMA=420
INICIO=$(date +%s)
while true; do
    TOTAL=$(docker compose ps --format '{{.Service}}' | wc -l)
    SANOS=$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -c 'healthy' || true)
    SIN_SONDA=$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -cE ' $' || true)

    if [ "$((SANOS + SIN_SONDA))" -ge "$TOTAL" ]; then
        echo "    todos los servicios con sonda estan sanos"
        break
    fi

    TRANSCURRIDO=$(( $(date +%s) - INICIO ))
    if [ "$TRANSCURRIDO" -gt "$ESPERA_MAXIMA" ]; then
        echo "    se agotaron los ${ESPERA_MAXIMA}s de espera; estado actual:" >&2
        docker compose ps
        exit 1
    fi
    echo "    ${SANOS}/${TOTAL} sanos (${TRANSCURRIDO}s)"
    sleep 10
done

# Que los contenedores esten sanos no basta: el gateway guarda una copia local
# del registro de Eureka y hasta refrescarla responde 503 para servicios que ya
# estan en pie. Se comprueba el enrutamiento de verdad antes de dar por listo el
# sistema, porque si no la evidencia se lanza en esa ventana y falla sin que
# haya nada roto.
echo "==> Comprobando que el gateway enrute hacia los canales y microservicios"
for intento in $(seq 1 40); do
    PENDIENTES=0
    for ruta in canal/web/panel/1 canal/movil/resumen/1 canal/cajero/saldo/101 \
                api/clientes/1 api/cuentas/101 api/pagos api/notificaciones; do
        CODIGO=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
                 "http://localhost:8080/${ruta}" || echo 000)
        # 401 es la respuesta correcta sin token: significa que la ruta existe y
        # el gateway la atendio. 503 significa que todavia no conoce el destino.
        [ "$CODIGO" = "401" ] || PENDIENTES=$((PENDIENTES + 1))
    done
    if [ "$PENDIENTES" -eq 0 ]; then
        echo "    las siete rutas responden"
        break
    fi
    echo "    ${PENDIENTES} ruta(s) sin destino todavia"
    sleep 5
done

echo
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
cat <<'FIN'

Plataforma operativa:

  Gateway (unica puerta de entrada) http://localhost:8080
  Servidor de autorizacion          http://localhost:9000
  Panel de Eureka                   http://localhost:8761
  Configuracion centralizada        http://localhost:8888/ms-pagos/default
  Panel de Kafka                    http://localhost:8090
  Lanzador del batch                http://localhost:8085/api/batch/ejecuciones
  PostgreSQL                        localhost:5436 (usuario y clave: bancoxyz)

  Swagger de cada servicio:
    http://localhost:8084/swagger-ui.html   (clientes)
    http://localhost:8081/swagger-ui.html   (cuentas)
    http://localhost:8082/swagger-ui.html   (pagos)
    http://localhost:8083/swagger-ui.html   (notificaciones)
    http://localhost:8091/swagger-ui.html   (canal web)
    http://localhost:8092/swagger-ui.html   (canal movil)
    http://localhost:8093/swagger-ui.html   (canal cajero)

  Siguiente paso: ejecutar la migracion del sistema legado

    ./scripts/migrar.sh

FIN
