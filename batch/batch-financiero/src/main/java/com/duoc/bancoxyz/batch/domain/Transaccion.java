package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Transaccion diaria ya validada y normalizada, lista para persistir. */
public record Transaccion(
        long id,
        LocalDate fecha,
        BigDecimal monto,
        String tipo,
        String fechaOrigen,
        String formatoDetectado) {
}
