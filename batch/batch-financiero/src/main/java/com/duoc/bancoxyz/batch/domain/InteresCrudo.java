package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;

/** Fila de {@code intereses_trimestrales.csv} tal como viene del sistema legacy. */
public record InteresCrudo(
        Integer cuentaId,
        String nombre,
        BigDecimal saldo,
        Integer edad,
        String tipo,
        String lineaOrigen) {
}
