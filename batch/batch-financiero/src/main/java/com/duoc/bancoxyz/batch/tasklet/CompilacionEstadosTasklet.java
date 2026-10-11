package com.duoc.bancoxyz.batch.tasklet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compila los movimientos validados en un estado de cuenta por cuenta y
 * ano: recuento por tipo de operacion, total de abonos, total de cargos,
 * saldo neto y ventana temporal cubierta.
 *
 * <p>El saldo neto se calcula sobre {@code monto_con_signo}, que el
 * procesador ya dejo con el signo contable correcto segun el tipo de
 * operacion. Asi el cuadre no depende del signo con que el sistema legacy
 * grabo el importe, que es justamente uno de los datos corruptos.</p>
 */
public class CompilacionEstadosTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(CompilacionEstadosTasklet.class);

    private final JdbcTemplate jdbcTemplate;

    public CompilacionEstadosTasklet(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();

        int estados = jdbcTemplate.update("""
                INSERT INTO estado_cuenta_anual
                    (cuenta_id, anio, total_movimientos, total_depositos, total_retiros,
                     total_compras, total_pagos, monto_abonos, monto_cargos, saldo_neto,
                     primer_movimiento, ultimo_movimiento, job_execution_id)
                SELECT m.cuenta_id, m.anio,
                       COUNT(*),
                       COUNT(*) FILTER (WHERE m.tipo_movimiento = 'deposito'),
                       COUNT(*) FILTER (WHERE m.tipo_movimiento = 'retiro'),
                       COUNT(*) FILTER (WHERE m.tipo_movimiento = 'compra'),
                       COUNT(*) FILTER (WHERE m.tipo_movimiento = 'pago'),
                       COALESCE(SUM(m.monto) FILTER (WHERE m.signo = 1), 0),
                       COALESCE(SUM(m.monto) FILTER (WHERE m.signo = -1), 0),
                       SUM(m.monto_con_signo),
                       MIN(m.fecha), MAX(m.fecha),
                       ?
                FROM movimiento_anual m
                GROUP BY m.cuenta_id, m.anio
                ON CONFLICT (cuenta_id, anio) DO UPDATE SET
                    total_movimientos = EXCLUDED.total_movimientos,
                    total_depositos = EXCLUDED.total_depositos,
                    total_retiros = EXCLUDED.total_retiros,
                    total_compras = EXCLUDED.total_compras,
                    total_pagos = EXCLUDED.total_pagos,
                    monto_abonos = EXCLUDED.monto_abonos,
                    monto_cargos = EXCLUDED.monto_cargos,
                    saldo_neto = EXCLUDED.saldo_neto,
                    primer_movimiento = EXCLUDED.primer_movimiento,
                    ultimo_movimiento = EXCLUDED.ultimo_movimiento,
                    job_execution_id = EXCLUDED.job_execution_id,
                    generado_en = now()
                """, jobExecutionId);

        log.info("Estados de cuenta anuales compilados: {} cuentas", estados);
        contribution.incrementWriteCount(estados);
        return RepeatStatus.FINISHED;
    }
}
