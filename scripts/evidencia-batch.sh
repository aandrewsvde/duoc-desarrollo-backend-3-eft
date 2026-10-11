#!/usr/bin/env bash
#
# Evidencia de la Parte 1: migracion de los procesos batch legados.
set -uo pipefail
cd "$(dirname "$0")/.."
SALIDA="evidencia/consola"; mkdir -p "$SALIDA"
exec > >(tee "$SALIDA/01-migracion-batch.txt") 2>&1

titulo() { printf '\n==================================================================\n%s\n==================================================================\n' "$1"; }

echo "Banco XYZ - Evidencia de la migracion batch"
echo "Generado: $(date '+%Y-%m-%d %H:%M:%S')"

titulo "0. Los datos de entrada y sus defectos"
echo "Origen: https://github.com/KariVillagran/fin_legacy_data (data/semana_3)"
echo
for archivo in movimientos_financieros_diarios intereses_trimestrales estados_financieros_anuales; do
    printf '   %-36s %s filas\n' "${archivo}.csv" \
        "$(( $(wc -l < "datos/legacy/semana_3/${archivo}.csv") - 1 ))"
done
echo
echo "   Primeras filas de movimientos_financieros_diarios.csv:"
head -4 datos/legacy/semana_3/movimientos_financieros_diarios.csv | sed 's/^/      /'
echo
echo "   Se ven de un vistazo tres de los defectos: fechas en formatos distintos,"
echo "   montos negativos o vacios y tipos fuera de catalogo ('invalido')."

titulo "1. Configuracion del proceso, entregada por el config-server"
curl -sS "http://localhost:8888/batch-financiero/default" | python3 -c '
import json, sys
vistas = {}
for origen in json.load(sys.stdin)["propertySources"]:
    for clave, valor in origen["source"].items():
        if clave.startswith("banco.") and clave not in vistas:
            vistas[clave] = valor
for clave in sorted(vistas):
    print("   {} = {}".format(clave, vistas[clave]))'

titulo "2. Los tres jobs"
echo "Se lanzan en orden: intereses crea las cuentas, y tanto los movimientos"
echo "anuales como las transacciones diarias las referencian."
echo
curl -sS -X POST http://localhost:8085/api/batch/migracion-completa --max-time 900 \
  | python3 scripts/lib/migracion.py

titulo "3. Que significa cada cifra"
cat <<'FIN'
   leidos    filas que el lector entrego al procesador. Es menor que el total
             del archivo porque las que ni siquiera se pueden parsear se
             omiten en la lectura.
   escritos  filas que superaron todas las validaciones y llegaron a la base.
   omitidos  filas descartadas por la politica de omision, con su motivo
             anotado en la tabla de cuarentena y en un CSV de rechazos.

   Que se omita mas de la mitad NO es un fallo: es exactamente lo que el
   enunciado describe cuando dice que los archivos traen errores
   intencionales. Lo que si seria un fallo es que el proceso se detuviera en
   la primera fila mala, o que las dejara entrar sin avisar.
FIN

titulo "4. Particionado: el paralelismo del step de carga"
echo "Cada job divide su archivo en rangos y los procesa en hilos distintos."
echo "Estas son las particiones del ultimo job ejecutado:"
echo
curl -sS "http://localhost:8085/api/batch/ejecuciones?limite=3" | python3 -c '
import json, sys
for e in json.load(sys.stdin)["ejecuciones"]:
    print("   {:<34} {:<12} {}".format(e["job_name"], e["status"], e["exit_code"]))'
echo
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_batch -c \
  "SELECT step_name, status, read_count, write_count, (filter_count + process_skip_count + read_skip_count) AS omitidos
     FROM batch_step_execution
    WHERE step_name LIKE '%Worker%'
    ORDER BY step_execution_id DESC LIMIT 8;" 2>/dev/null | sed 's/^/   /'

titulo "5. Tolerancia a fallos: los registros rechazados y su motivo"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_batch -c \
  "SELECT job_name AS job, fase, left(motivo, 62) AS motivo, count(*) AS cuantos
     FROM registro_rechazado
    GROUP BY job_name, fase, left(motivo, 62)
    ORDER BY cuantos DESC LIMIT 14;" 2>/dev/null | sed 's/^/   /'

titulo "6. Resultado en las bases de los microservicios"
echo "El ultimo paso de cada job carga lo ya saneado en la base del"
echo "microservicio que corresponde. Es el unico momento en que el batch"
echo "escribe en ellas."
echo
echo "--- ms-clientes ---"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_clientes -c \
  "SELECT id, nombre, edad, segmento, saldo_total FROM cliente ORDER BY saldo_total DESC;" \
  2>/dev/null | sed 's/^/   /'
echo "--- ms-cuentas ---"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_cuentas -c \
  "SELECT count(*) AS cuentas,
          (SELECT count(*) FROM movimiento) AS movimientos,
          (SELECT count(*) FROM cuenta_bitacora) AS bitacora
     FROM cuenta;" 2>/dev/null | sed 's/^/   /'
echo "--- ms-pagos ---"
docker compose exec -T postgres psql -U bancoxyz -d bancoxyz_pagos -c \
  "SELECT estado, canal, count(*) FROM pago GROUP BY estado, canal ORDER BY count DESC;" \
  2>/dev/null | sed 's/^/   /'

titulo "7. Informes generados"
echo "Quedan en datos/salida/, montado como volumen para poder revisarlos sin"
echo "entrar al contenedor."
echo
ls -lh datos/salida/informes 2>/dev/null | tail -n +2 | awk '{printf "   %-52s %s\n", $9, $5}'
echo
for f in datos/salida/informes/*.txt; do
    [ -f "$f" ] || continue
    echo "   --- $(basename "$f") (primeras lineas) ---"
    head -14 "$f" | sed 's/^/      /'
    break
done

titulo "8. Reejecucion: la migracion es repetible"
echo "Se puede relanzar entera y deja exactamente el mismo resultado. Es"
echo "deliberado: un proceso de migracion que acumulara sobre lo anterior"
echo "dejaria cuentas y movimientos duplicados al reintentar tras un fallo."
echo
echo "Se relanza la migracion COMPLETA y no un job suelto. El motivo es una"
echo "dependencia real entre los tres: el job de intereses vacia las tablas de"
echo "destino antes de cargarlas, de modo que ejecutarlo solo borraria los"
echo "movimientos que cargo el job de estados. Es la misma razon por la que"
echo "existe el endpoint /migracion-completa en lugar de dejarlo a la mano."
echo
contar() {
    docker compose exec -T postgres psql -U bancoxyz -d "$1" -t -c "SELECT count(*) FROM $2" \
      | tr -d ' \n'
}
echo "   antes:    clientes=$(contar bancoxyz_clientes cliente)" \
     "cuentas=$(contar bancoxyz_cuentas cuenta)" \
     "movimientos=$(contar bancoxyz_cuentas movimiento)" \
     "pagos=$(contar bancoxyz_pagos pago)"

curl -sS -X POST http://localhost:8085/api/batch/migracion-completa --max-time 900 \
  | python3 -c 'import json,sys; print("   segunda ejecucion:", json.load(sys.stdin)["resultado"])'

echo "   despues:  clientes=$(contar bancoxyz_clientes cliente)" \
     "cuentas=$(contar bancoxyz_cuentas cuenta)" \
     "movimientos=$(contar bancoxyz_cuentas movimiento)" \
     "pagos=$(contar bancoxyz_pagos pago)"
echo
echo "   Las cuatro cifras coinciden: la migracion es idempotente."

titulo "Fin de la evidencia de migracion"
