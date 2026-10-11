package com.duoc.bancoxyz.batch.tasklet;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deteccion de anomalias sobre las transacciones ya validadas.
 *
 * <p>Distincion importante: los registros <i>defectuosos</i> (monto vacio,
 * tipo fuera de catalogo, fecha imposible) no llegan aqui, se quedaron en
 * cuarentena durante la carga. Este step busca lo otro: transacciones
 * formalmente correctas que aun asi merecen revision del area de riesgo.</p>
 *
 * <p>Se aplican tres reglas:</p>
 * <ol>
 *   <li><b>MONTO_ELEVADO</b>: importes en el tramo superior configurado.</li>
 *   <li><b>DUPLICADO_LOGICO</b>: mismo dia, mismo monto y mismo tipo. No es
 *       un duplicado de archivo (los identificadores difieren), sino el
 *       patron tipico de un cargo emitido dos veces.</li>
 *   <li><b>DIA_ATIPICO</b>: jornadas cuyo monto total se aparta mas de dos
 *       desviaciones estandar de la media del periodo.</li>
 * </ol>
 */
public class DeteccionAnomaliasTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DeteccionAnomaliasTasklet.class);

    private final JdbcTemplate jdbcTemplate;
    private final BigDecimal montoAnomaloDesde;

    public DeteccionAnomaliasTasklet(JdbcTemplate jdbcTemplate, BigDecimal montoAnomaloDesde) {
        this.jdbcTemplate = jdbcTemplate;
        this.montoAnomaloDesde = montoAnomaloDesde;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();

        int montosElevados = jdbcTemplate.update("""
                INSERT INTO anomalia_transaccion (transaccion_id, fecha, tipo_anomalia, detalle, job_execution_id)
                SELECT t.id, t.fecha, 'MONTO_ELEVADO',
                       'Monto ' || t.monto || ' alcanza o supera el umbral de revision ' || ?,
                       ?
                FROM transaccion t
                WHERE t.monto >= ?
                """, montoAnomaloDesde, jobExecutionId, montoAnomaloDesde);

        int duplicadosLogicos = jdbcTemplate.update("""
                INSERT INTO anomalia_transaccion (transaccion_id, fecha, tipo_anomalia, detalle, job_execution_id)
                SELECT t.id, t.fecha, 'DUPLICADO_LOGICO',
                       'Existe otra transaccion del mismo dia, monto y tipo (' || t.tipo || ' ' || t.monto || ')',
                       ?
                FROM transaccion t
                WHERE EXISTS (
                    SELECT 1 FROM transaccion o
                    WHERE o.fecha = t.fecha AND o.monto = t.monto AND o.tipo = t.tipo AND o.id < t.id
                )
                """, jobExecutionId);

        int diasAtipicos = jdbcTemplate.update("""
                INSERT INTO anomalia_transaccion (transaccion_id, fecha, tipo_anomalia, detalle, job_execution_id)
                WITH totales AS (
                    SELECT fecha, SUM(monto) AS monto_dia FROM transaccion GROUP BY fecha
                ), estadisticas AS (
                    SELECT AVG(monto_dia) AS media, COALESCE(STDDEV_POP(monto_dia), 0) AS desviacion FROM totales
                )
                SELECT NULL, t.fecha, 'DIA_ATIPICO',
                       'Monto diario ' || ROUND(t.monto_dia, 2) || ' se aparta mas de 2 desviaciones de la media '
                           || ROUND(e.media, 2),
                       ?
                FROM totales t CROSS JOIN estadisticas e
                WHERE e.desviacion > 0
                  AND ABS(t.monto_dia - e.media) > 2 * e.desviacion
                """, jobExecutionId);

        int total = montosElevados + duplicadosLogicos + diasAtipicos;
        log.info("Anomalias detectadas: {} por monto elevado, {} por duplicado logico, {} por dia atipico (total {})",
                montosElevados, duplicadosLogicos, diasAtipicos, total);

        contribution.incrementWriteCount(total);
        return RepeatStatus.FINISHED;
    }
}
