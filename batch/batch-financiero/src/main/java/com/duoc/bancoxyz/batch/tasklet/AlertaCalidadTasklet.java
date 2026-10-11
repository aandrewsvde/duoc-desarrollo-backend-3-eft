package com.duoc.bancoxyz.batch.tasklet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rama de excepcion del flujo: se ejecuta cuando el decider determina que
 * la proporcion de registros validos quedo por debajo del umbral aceptable.
 *
 * <p>En ese escenario no tiene sentido seguir consolidando: un resumen
 * calculado sobre una fraccion minima del archivo es peor que no tener
 * resumen, porque parece valido. El step deja el diagnostico en el log,
 * con el desglose de motivos de rechazo, y el job termina en FAILED para
 * que el operador lo vea y pueda reanudarlo tras corregir el origen.</p>
 */
public class AlertaCalidadTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(AlertaCalidadTasklet.class);

    private final JdbcTemplate jdbcTemplate;
    private final String jobName;

    public AlertaCalidadTasklet(JdbcTemplate jdbcTemplate, String jobName) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobName = jobName;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();

        log.error("CALIDAD INSUFICIENTE en el job '{}' (JobExecutionId={}). Desglose de rechazos:",
                jobName, jobExecutionId);

        jdbcTemplate.query("""
                        SELECT fase, excepcion, COUNT(*) AS total
                        FROM registro_rechazado
                        WHERE job_name = ? AND job_execution_id = ?
                        GROUP BY fase, excepcion
                        ORDER BY total DESC
                        """,
                rs -> {
                    log.error("  {} / {} -> {} registros", rs.getString("fase"),
                            rs.getString("excepcion"), rs.getLong("total"));
                },
                jobName, jobExecutionId);

        log.error("El job termina en FAILED sin consolidar. Corrija el archivo de origen y reejecute.");
        return RepeatStatus.FINISHED;
    }
}
