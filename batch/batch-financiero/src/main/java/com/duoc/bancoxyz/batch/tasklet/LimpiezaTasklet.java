package com.duoc.bancoxyz.batch.tasklet;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Primer step de cada job: deja limpias las tablas destino de ese proceso.
 *
 * <p>Es lo que convierte a los jobs en <b>re-ejecutables</b>. Un batch de
 * migracion se corre muchas veces mientras se afinan las reglas, y sin
 * este paso cada corrida acumularia los resultados de la anterior: los
 * resumenes saldrian inflados y nadie podria comparar dos ejecuciones.
 * Con el, el resultado depende solo del archivo de entrada y de la
 * configuracion, no de cuantas veces se ejecuto antes.</p>
 *
 * <p>No toca las tablas {@code BATCH_*} de Spring Batch: el historial de
 * ejecuciones se conserva intacto.</p>
 */
public class LimpiezaTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaTasklet.class);

    private final JdbcTemplate jdbcTemplate;
    private final String jobName;
    private final List<String> tablas;

    public LimpiezaTasklet(JdbcTemplate jdbcTemplate, String jobName, List<String> tablas) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobName = jobName;
        this.tablas = tablas;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        for (String tabla : tablas) {
            int borradas = jdbcTemplate.update("DELETE FROM " + tabla);
            log.info("Limpieza previa: {} filas eliminadas de {}", borradas, tabla);
        }
        int cuarentena = jdbcTemplate.update("DELETE FROM registro_rechazado WHERE job_name = ?", jobName);
        log.info("Limpieza previa: {} filas de cuarentena eliminadas para el job '{}'", cuarentena, jobName);
        return RepeatStatus.FINISHED;
    }
}
