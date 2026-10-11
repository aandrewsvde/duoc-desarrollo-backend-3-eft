package com.duoc.bancoxyz.batch.migracion;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Carga en ms-cuentas el historial de movimientos del ejercicio.
 *
 * <p>Depende de que la migracion de cuentas se haya ejecutado antes: un
 * movimiento referencia una cuenta y la tabla de destino lo exige por clave
 * foranea. Esa dependencia entre jobs es real y se documenta en lugar de
 * esconderse, porque es lo que obliga a un orden concreto de ejecucion.</p>
 *
 * <p>Los movimientos entran con origen LEGACY y sin identificador de pago: no
 * nacieron de una operacion de este sistema sino del arrastre de la migracion,
 * y confundirlos con pagos propios haria cuadrar mal cualquier conciliacion.</p>
 */
public class MigracionMovimientosTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(MigracionMovimientosTasklet.class);

    /** Tamano del lote de insercion; ver nota sobre el rendimiento mas abajo. */
    private static final int TAMANO_LOTE = 500;

    private final JdbcTemplate origen;
    private final JdbcTemplate destinoCuentas;

    public MigracionMovimientosTasklet(JdbcTemplate origen, JdbcTemplate destinoCuentas) {
        this.origen = origen;
        this.destinoCuentas = destinoCuentas;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) {
        // Solo los movimientos de cuentas que existen en el destino. El archivo
        // anual referencia cuentas que el archivo de intereses descarto por
        // datos invalidos, y sin este filtro la insercion fallaria entera por
        // la clave foranea, perdiendo tambien los movimientos correctos.
        List<Integer> cuentasValidas = destinoCuentas.queryForList(
                "SELECT numero_cuenta FROM cuenta", Integer.class);

        if (cuentasValidas.isEmpty()) {
            log.warn("No hay cuentas en el destino; ejecute antes el job de intereses");
            return RepeatStatus.FINISHED;
        }

        List<Map<String, Object>> movimientos = origen.queryForList("""
                SELECT cuenta_id, fecha, tipo_movimiento, signo, monto, descripcion
                  FROM movimiento_anual
                 ORDER BY cuenta_id, fecha, id
                """);

        destinoCuentas.update("DELETE FROM movimiento WHERE origen = 'LEGACY'");

        List<Object[]> lote = new java.util.ArrayList<>(TAMANO_LOTE);
        int migrados = 0;
        int omitidos = 0;

        for (Map<String, Object> fila : movimientos) {
            int cuenta = ((Number) fila.get("cuenta_id")).intValue();
            if (!cuentasValidas.contains(cuenta)) {
                omitidos++;
                continue;
            }
            lote.add(new Object[]{
                    cuenta,
                    fila.get("fecha"),
                    ((String) fila.get("tipo_movimiento")).toUpperCase(),
                    ((Number) fila.get("signo")).intValue(),
                    fila.get("monto"),
                    fila.get("descripcion")});

            if (lote.size() == TAMANO_LOTE) {
                migrados += insertar(lote);
                lote.clear();
            }
        }
        if (!lote.isEmpty()) {
            migrados += insertar(lote);
        }

        log.info("Movimientos migrados: {}. Omitidos por cuenta inexistente en el destino: {}",
                migrados, omitidos);
        contribucion.incrementWriteCount(migrados);
        return RepeatStatus.FINISHED;
    }

    /**
     * Inserta por lotes y no fila a fila.
     *
     * <p>Con ~700 movimientos la diferencia ya se nota, y con el volumen de un
     * banco real es la diferencia entre minutos y horas: cada insercion
     * individual es un viaje de red completo, mientras que un lote los agrupa
     * en uno solo.</p>
     */
    private int insertar(List<Object[]> lote) {
        destinoCuentas.batchUpdate("""
                INSERT INTO movimiento (cuenta_id, pago_id, fecha, tipo, signo,
                                        monto, descripcion, origen)
                VALUES (?, NULL, ?, ?, ?, ?, ?, 'LEGACY')
                """, lote);
        return lote.size();
    }
}
