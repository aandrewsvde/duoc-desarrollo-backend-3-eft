package com.duoc.bancoxyz.batch.config;

import com.duoc.bancoxyz.batch.domain.EstadoCuentaAnual;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lectores y escritores de los informes de salida.
 *
 * <p>Estos steps si recorren la base con un {@code ItemReader} en lugar de
 * resolverse en SQL, porque su salida es un archivo linea a linea: el
 * cursor entrega las filas de a una y el writer las va volcando, sin
 * materializar el informe completo en memoria.</p>
 */
@Configuration
public class InformesConfig {

    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final BancoBatchProperties propiedades;

    public InformesConfig(DataSource dataSource, JdbcTemplate jdbcTemplate, BancoBatchProperties propiedades) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.propiedades = propiedades;
    }

    // -----------------------------------------------------------------
    // Job 1 - reporte diario de transacciones
    // -----------------------------------------------------------------
    @Bean
    @StepScope
    public JdbcCursorItemReader<Object[]> lectorResumenDiario() {
        return new JdbcCursorItemReaderBuilder<Object[]>()
                .name("lectorResumenDiario")
                .dataSource(dataSource)
                .sql("""
                     SELECT fecha, total_transacciones, total_creditos, total_debitos,
                            monto_total, monto_creditos, monto_debitos, monto_neto,
                            monto_promedio, monto_maximo, monto_minimo, transacciones_anomalas
                     FROM resumen_transaccion_diaria
                     ORDER BY fecha
                     """)
                .rowMapper((rs, fila) -> new Object[]{
                        rs.getDate("fecha").toLocalDate(),
                        rs.getInt("total_transacciones"),
                        rs.getInt("total_creditos"),
                        rs.getInt("total_debitos"),
                        rs.getBigDecimal("monto_total"),
                        rs.getBigDecimal("monto_creditos"),
                        rs.getBigDecimal("monto_debitos"),
                        rs.getBigDecimal("monto_neto"),
                        rs.getBigDecimal("monto_promedio"),
                        rs.getBigDecimal("monto_maximo"),
                        rs.getBigDecimal("monto_minimo"),
                        rs.getInt("transacciones_anomalas")})
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemWriter<Object[]> escritorReporteDiario(
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {

        String ruta = "%s/informes/reporte_transacciones_diarias_%d.csv"
                .formatted(propiedades.getSalida().getDirectorio(), jobExecutionId);

        return new FlatFileItemWriterBuilder<Object[]>()
                .name("escritorReporteDiario")
                .resource(new FileSystemResource(ruta))
                .encoding("UTF-8")
                .headerCallback(writer -> writer.write(
                        "fecha,total_transacciones,total_creditos,total_debitos,monto_total,"
                                + "monto_creditos,monto_debitos,monto_neto,monto_promedio,"
                                + "monto_maximo,monto_minimo,transacciones_anomalas"))
                .lineAggregator(fila -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < fila.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(fila[i]);
                    }
                    return sb.toString();
                })
                .shouldDeleteIfExists(true)
                .build();
    }

    // -----------------------------------------------------------------
    // Job 3 - informe detallado de estados de cuenta para auditoria
    // -----------------------------------------------------------------
    @Bean
    @StepScope
    public JdbcCursorItemReader<EstadoCuentaAnual> lectorEstadosCuenta() {
        return new JdbcCursorItemReaderBuilder<EstadoCuentaAnual>()
                .name("lectorEstadosCuenta")
                .dataSource(dataSource)
                .sql("""
                     SELECT cuenta_id, anio, total_movimientos, total_depositos, total_retiros,
                            total_compras, total_pagos, monto_abonos, monto_cargos, saldo_neto,
                            primer_movimiento, ultimo_movimiento
                     FROM estado_cuenta_anual
                     ORDER BY cuenta_id, anio
                     """)
                .rowMapper((rs, fila) -> new EstadoCuentaAnual(
                        rs.getInt("cuenta_id"),
                        rs.getInt("anio"),
                        rs.getInt("total_movimientos"),
                        rs.getInt("total_depositos"),
                        rs.getInt("total_retiros"),
                        rs.getInt("total_compras"),
                        rs.getInt("total_pagos"),
                        rs.getBigDecimal("monto_abonos"),
                        rs.getBigDecimal("monto_cargos"),
                        rs.getBigDecimal("saldo_neto"),
                        rs.getDate("primer_movimiento").toLocalDate(),
                        rs.getDate("ultimo_movimiento").toLocalDate()))
                .build();
    }

    /**
     * Informe de auditoria en texto plano con cabecera de contexto y pie
     * con los totales del ejercicio: es el entregable que el enunciado
     * pide "para auditorias", y por eso lleva sello de ejecucion y
     * trazabilidad al JobExecution que lo genero.
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<EstadoCuentaAnual> escritorInformeAuditoria(
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {

        String ruta = "%s/informes/estados_cuenta_anuales_%d.txt"
                .formatted(propiedades.getSalida().getDirectorio(), jobExecutionId);
        int anio = propiedades.getReglas().getAnioEsperado();

        return new FlatFileItemWriterBuilder<EstadoCuentaAnual>()
                .name("escritorInformeAuditoria")
                .resource(new FileSystemResource(ruta))
                .encoding("UTF-8")
                .headerCallback(writer -> {
                    writer.write("==========================================================================================================\n");
                    writer.write("  BANCO XYZ - INFORME ANUAL DE ESTADOS DE CUENTA PARA AUDITORIA\n");
                    writer.write("  Ejercicio: " + anio + "\n");
                    writer.write("  Generado: " + LocalDateTime.now().format(SELLO) + "\n");
                    writer.write("  JobExecutionId: " + jobExecutionId + "\n");
                    writer.write("  Origen: estados_financieros_anuales.csv (sistema legacy Banco XYZ)\n");
                    writer.write("==========================================================================================================\n");
                    writer.write(String.format("%-8s %-6s %6s %6s %6s %6s %6s %14s %14s %14s %12s %12s",
                            "CUENTA", "ANIO", "MOVS", "DEP", "RET", "COM", "PAG",
                            "ABONOS", "CARGOS", "SALDO NETO", "DESDE", "HASTA"));
                    writer.write("\n----------------------------------------------------------------------------------------------------------");
                })
                .lineAggregator(estado -> String.format(
                        "%-8d %-6d %6d %6d %6d %6d %6d %14s %14s %14s %12s %12s",
                        estado.cuentaId(), estado.anio(), estado.totalMovimientos(),
                        estado.totalDepositos(), estado.totalRetiros(), estado.totalCompras(),
                        estado.totalPagos(), estado.montoAbonos().toPlainString(),
                        estado.montoCargos().toPlainString(), estado.saldoNeto().toPlainString(),
                        estado.primerMovimiento(), estado.ultimoMovimiento()))
                .footerCallback(writer -> {
                    writer.write("----------------------------------------------------------------------------------------------------------\n");
                    Totales totales = totalesDelEjercicio();
                    writer.write(String.format("  TOTALES: %d cuentas | %d movimientos | abonos %s | cargos %s | saldo neto %s%n",
                            totales.cuentas(), totales.movimientos(), totales.abonos(),
                            totales.cargos(), totales.saldoNeto()));
                    writer.write(String.format("  Movimientos descartados y documentados en la tabla registro_rechazado: %d%n",
                            movimientosRechazados(jobExecutionId)));
                    writer.write("==========================================================================================================");
                })
                .shouldDeleteIfExists(true)
                .build();
    }

    private record Totales(long cuentas, long movimientos, BigDecimal abonos, BigDecimal cargos, BigDecimal saldoNeto) {
    }

    private Totales totalesDelEjercicio() {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS cuentas,
                               COALESCE(SUM(total_movimientos), 0) AS movimientos,
                               COALESCE(SUM(monto_abonos), 0) AS abonos,
                               COALESCE(SUM(monto_cargos), 0) AS cargos,
                               COALESCE(SUM(saldo_neto), 0) AS saldo_neto
                        FROM estado_cuenta_anual
                        """,
                (rs, fila) -> new Totales(
                        rs.getLong("cuentas"),
                        rs.getLong("movimientos"),
                        rs.getBigDecimal("abonos"),
                        rs.getBigDecimal("cargos"),
                        rs.getBigDecimal("saldo_neto")));
    }

    private long movimientosRechazados(Long jobExecutionId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registro_rechazado WHERE job_execution_id = ?",
                Long.class, jobExecutionId);
        return total == null ? 0L : total;
    }
}
