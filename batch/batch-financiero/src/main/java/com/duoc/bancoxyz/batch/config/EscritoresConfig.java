package com.duoc.bancoxyz.batch.config;

import com.duoc.bancoxyz.batch.domain.CuentaInteres;
import com.duoc.bancoxyz.batch.domain.MovimientoAnual;
import com.duoc.bancoxyz.batch.domain.Transaccion;
import java.sql.Date;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Escritores JDBC por lote hacia PostgreSQL.
 *
 * <p>Se usa {@link JdbcBatchItemWriter} y no una escritura fila a fila: el
 * writer agrupa todo el chunk en un unico {@code addBatch/executeBatch}
 * dentro de la transaccion del chunk, que es de donde viene la mayor parte
 * del rendimiento del proceso.</p>
 *
 * <p>Los escritores son {@link StepScope} porque sellan cada fila con el
 * {@code jobExecutionId} de la ejecucion en curso: sin ese dato no se
 * puede auditar que produjo cada ejecucion ni comparar dos corridas.</p>
 */
@Configuration
public class EscritoresConfig {

    private final DataSource dataSource;

    public EscritoresConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * La clausula {@code ON CONFLICT DO NOTHING} hace la escritura
     * idempotente: si un chunk se reintenta tras un fallo transitorio, o
     * el job se reanuda, reinsertar una transaccion ya confirmada no
     * rompe la carga. Por eso tambien se desactiva la verificacion de
     * filas afectadas, que asumiria que toda sentencia modifica una fila.
     */
    @Bean
    @StepScope
    public JdbcBatchItemWriter<Transaccion> escritorTransacciones(
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {

        JdbcBatchItemWriter<Transaccion> writer = new JdbcBatchItemWriterBuilder<Transaccion>()
                .dataSource(dataSource)
                .sql("""
                     INSERT INTO transaccion
                         (id, fecha, monto, tipo, fecha_origen, formato_detectado, job_execution_id)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (id) DO NOTHING
                     """)
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setLong(1, item.id());
                    ps.setDate(2, Date.valueOf(item.fecha()));
                    ps.setBigDecimal(3, item.monto());
                    ps.setString(4, item.tipo());
                    ps.setString(5, item.fechaOrigen());
                    ps.setString(6, item.formatoDetectado());
                    ps.setObject(7, jobExecutionId);
                })
                .build();
        writer.setAssertUpdates(false);
        return writer;
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<CuentaInteres> escritorCuentaInteres(
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {

        return new JdbcBatchItemWriterBuilder<CuentaInteres>()
                .dataSource(dataSource)
                .sql("""
                     INSERT INTO cuenta_interes
                         (cuenta_id, nombre, tipo_cuenta, edad, saldo_inicial,
                          tasa_aplicada, interes_calculado, saldo_final, periodo, job_execution_id)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setInt(1, item.cuentaId());
                    ps.setString(2, item.nombre());
                    ps.setString(3, item.tipoCuenta());
                    ps.setInt(4, item.edad());
                    ps.setBigDecimal(5, item.saldoInicial());
                    ps.setBigDecimal(6, item.tasaAplicada());
                    ps.setBigDecimal(7, item.interesCalculado());
                    ps.setBigDecimal(8, item.saldoFinal());
                    ps.setString(9, item.periodo());
                    ps.setObject(10, jobExecutionId);
                })
                .build();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<MovimientoAnual> escritorMovimientoAnual(
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {

        return new JdbcBatchItemWriterBuilder<MovimientoAnual>()
                .dataSource(dataSource)
                .sql("""
                     INSERT INTO movimiento_anual
                         (cuenta_id, fecha, anio, tipo_movimiento, signo, monto,
                          monto_con_signo, descripcion, fecha_origen, formato_detectado, job_execution_id)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setInt(1, item.cuentaId());
                    ps.setDate(2, Date.valueOf(item.fecha()));
                    ps.setInt(3, item.anio());
                    ps.setString(4, item.tipoMovimiento());
                    ps.setInt(5, item.signo());
                    ps.setBigDecimal(6, item.monto());
                    ps.setBigDecimal(7, item.montoConSigno());
                    ps.setString(8, item.descripcion());
                    ps.setString(9, item.fechaOrigen());
                    ps.setString(10, item.formatoDetectado());
                    ps.setObject(11, jobExecutionId);
                })
                .build();
    }
}
