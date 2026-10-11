package com.duoc.bancoxyz.batch.tasklet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consolida el reporte diario de transacciones: una fila por dia con
 * volumenes, importes por tipo, estadisticos y el numero de anomalias
 * detectadas en el step anterior.
 *
 * <p>La agregacion se resuelve en la base de datos y no trayendo filas a
 * memoria: el motor ya tiene los datos indexados por fecha y el volumen de
 * salida (una fila por dia) es varios ordenes de magnitud menor que la
 * entrada. Traerlo al proceso solo agregaria trafico y presion de memoria
 * sin ganar nada.</p>
 */
public class ResumenDiarioTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ResumenDiarioTasklet.class);

    private final JdbcTemplate jdbcTemplate;

    public ResumenDiarioTasklet(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();

        int dias = jdbcTemplate.update("""
                INSERT INTO resumen_transaccion_diaria
                    (fecha, total_transacciones, total_creditos, total_debitos,
                     monto_total, monto_creditos, monto_debitos, monto_neto,
                     monto_promedio, monto_maximo, monto_minimo, transacciones_anomalas, job_execution_id)
                SELECT t.fecha,
                       COUNT(*),
                       COUNT(*) FILTER (WHERE t.tipo = 'credito'),
                       COUNT(*) FILTER (WHERE t.tipo = 'debito'),
                       SUM(t.monto),
                       COALESCE(SUM(t.monto) FILTER (WHERE t.tipo = 'credito'), 0),
                       COALESCE(SUM(t.monto) FILTER (WHERE t.tipo = 'debito'), 0),
                       COALESCE(SUM(t.monto) FILTER (WHERE t.tipo = 'credito'), 0)
                           - COALESCE(SUM(t.monto) FILTER (WHERE t.tipo = 'debito'), 0),
                       ROUND(AVG(t.monto), 2),
                       MAX(t.monto),
                       MIN(t.monto),
                       COALESCE((SELECT COUNT(*) FROM anomalia_transaccion a WHERE a.fecha = t.fecha), 0),
                       ?
                FROM transaccion t
                GROUP BY t.fecha
                ON CONFLICT (fecha) DO UPDATE SET
                    total_transacciones = EXCLUDED.total_transacciones,
                    total_creditos = EXCLUDED.total_creditos,
                    total_debitos = EXCLUDED.total_debitos,
                    monto_total = EXCLUDED.monto_total,
                    monto_creditos = EXCLUDED.monto_creditos,
                    monto_debitos = EXCLUDED.monto_debitos,
                    monto_neto = EXCLUDED.monto_neto,
                    monto_promedio = EXCLUDED.monto_promedio,
                    monto_maximo = EXCLUDED.monto_maximo,
                    monto_minimo = EXCLUDED.monto_minimo,
                    transacciones_anomalas = EXCLUDED.transacciones_anomalas,
                    job_execution_id = EXCLUDED.job_execution_id,
                    generado_en = now()
                """, jobExecutionId);

        log.info("Reporte diario consolidado: {} jornadas resumidas", dias);
        contribution.incrementWriteCount(dias);
        return RepeatStatus.FINISHED;
    }
}
