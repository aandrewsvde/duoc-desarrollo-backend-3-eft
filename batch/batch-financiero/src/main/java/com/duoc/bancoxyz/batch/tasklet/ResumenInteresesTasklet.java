package com.duoc.bancoxyz.batch.tasklet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consolida el resultado del calculo de intereses por tipo de cuenta:
 * cuantas cuentas se procesaron, con que tasa, y cuanto suman el saldo
 * inicial, el interes y el saldo final del periodo. Es el cuadre que
 * revisa contabilidad antes de dar por cerrado el mes.
 */
public class ResumenInteresesTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ResumenInteresesTasklet.class);

    private final JdbcTemplate jdbcTemplate;

    public ResumenInteresesTasklet(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();

        int filas = jdbcTemplate.update("""
                INSERT INTO resumen_interes_mensual
                    (periodo, tipo_cuenta, total_cuentas, tasa_aplicada,
                     saldo_inicial_total, interes_total, saldo_final_total, job_execution_id)
                SELECT c.periodo, c.tipo_cuenta, COUNT(*), MAX(c.tasa_aplicada),
                       SUM(c.saldo_inicial), SUM(c.interes_calculado), SUM(c.saldo_final), ?
                FROM cuenta_interes c
                GROUP BY c.periodo, c.tipo_cuenta
                ON CONFLICT (periodo, tipo_cuenta) DO UPDATE SET
                    total_cuentas = EXCLUDED.total_cuentas,
                    tasa_aplicada = EXCLUDED.tasa_aplicada,
                    saldo_inicial_total = EXCLUDED.saldo_inicial_total,
                    interes_total = EXCLUDED.interes_total,
                    saldo_final_total = EXCLUDED.saldo_final_total,
                    job_execution_id = EXCLUDED.job_execution_id,
                    generado_en = now()
                """, jobExecutionId);

        log.info("Resumen de intereses consolidado: {} combinaciones periodo/tipo de cuenta", filas);
        contribution.incrementWriteCount(filas);
        return RepeatStatus.FINISHED;
    }
}
