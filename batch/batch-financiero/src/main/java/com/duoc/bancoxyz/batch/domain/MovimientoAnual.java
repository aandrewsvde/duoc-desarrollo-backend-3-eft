package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Movimiento anual validado, normalizado y clasificado en abono/cargo. */
public record MovimientoAnual(
        int cuentaId,
        LocalDate fecha,
        int anio,
        String tipoMovimiento,
        int signo,
        BigDecimal monto,
        BigDecimal montoConSigno,
        String descripcion,
        String fechaOrigen,
        String formatoDetectado) {
}
