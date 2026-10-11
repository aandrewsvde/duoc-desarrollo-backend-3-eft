package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;

/** Fila de {@code movimientos_financieros_diarios.csv} tal como viene del sistema legacy. */
public record TransaccionCruda(
        Long id,
        String fecha,
        BigDecimal monto,
        String tipo,
        String lineaOrigen) {
}
