package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;

/** Fila de {@code estados_financieros_anuales.csv} tal como viene del sistema legacy. */
public record MovimientoCrudo(
        Integer cuentaId,
        String fecha,
        String transaccion,
        BigDecimal monto,
        String descripcion,
        String lineaOrigen) {
}
