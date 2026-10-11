package com.duoc.bancoxyz.batch.tasklet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Elimina los registros duplicados exactos que trae {@code intereses_trimestrales.csv}
 * y los deja documentados en la cuarentena.
 *
 * <p>La deduplicacion se hace aqui, contra la base, y no dentro del
 * {@code ItemProcessor}, por dos razones concretas: el step de carga esta
 * particionado, de modo que las dos copias de un registro pueden caer en
 * particiones distintas y un filtro en memoria por particion no las veria;
 * y un procesador con estado compartido daria resultados distintos segun
 * como se repartan los chunks o cuantos se reviertan por una omision.
 * Resolverlo en SQL sobre el conjunto ya cargado es determinista.</p>
 *
 * <p>Se conserva la primera aparicion (menor id) y se descartan las
 * siguientes: aplicar el interes dos veces a la misma cuenta duplicaria el
 * abono o el cargo.</p>
 */
public class DeduplicacionInteresesTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DeduplicacionInteresesTasklet.class);

    private static final String CLAVE_NATURAL = "cuenta_id, nombre, tipo_cuenta, edad, saldo_inicial";

    private final JdbcTemplate jdbcTemplate;
    private final String jobName;

    public DeduplicacionInteresesTasklet(JdbcTemplate jdbcTemplate, String jobName) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobName = jobName;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
        String stepName = chunkContext.getStepContext().getStepExecution().getStepName();

        int documentados = jdbcTemplate.update("""
                INSERT INTO registro_rechazado
                    (job_execution_id, job_name, step_name, fase, linea_origen, motivo, excepcion, hilo)
                SELECT ?, ?, ?, 'PROCESO',
                       c.cuenta_id || ',' || c.nombre || ',' || c.saldo_inicial || ',' || c.edad || ',' || c.tipo_cuenta,
                       'Registro duplicado exacto en el archivo de origen; se conserva la primera aparicion',
                       'DuplicadoDetectado',
                       ?
                FROM cuenta_interes c
                WHERE EXISTS (
                    SELECT 1 FROM cuenta_interes o
                    WHERE o.cuenta_id = c.cuenta_id
                      AND o.nombre = c.nombre
                      AND o.tipo_cuenta = c.tipo_cuenta
                      AND o.edad = c.edad
                      AND o.saldo_inicial = c.saldo_inicial
                      AND o.id < c.id
                )
                """, jobExecutionId, jobName, stepName, Thread.currentThread().getName());

        int eliminados = jdbcTemplate.update("""
                DELETE FROM cuenta_interes c
                WHERE EXISTS (
                    SELECT 1 FROM cuenta_interes o
                    WHERE o.cuenta_id = c.cuenta_id
                      AND o.nombre = c.nombre
                      AND o.tipo_cuenta = c.tipo_cuenta
                      AND o.edad = c.edad
                      AND o.saldo_inicial = c.saldo_inicial
                      AND o.id < c.id
                )
                """);

        log.info("Deduplicacion por clave natural ({}): {} duplicados documentados y {} eliminados",
                CLAVE_NATURAL, documentados, eliminados);
        contribution.incrementWriteCount(eliminados);
        return RepeatStatus.FINISHED;
    }
}
