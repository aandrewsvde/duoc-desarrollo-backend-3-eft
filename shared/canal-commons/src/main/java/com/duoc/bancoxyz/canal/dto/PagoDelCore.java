package com.duoc.bancoxyz.canal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/** Pago tal como lo devuelve ms-pagos. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PagoDelCore(
        String id,
        int cuentaId,
        String tipo,
        BigDecimal monto,
        String moneda,
        String canal,
        String estado,
        String motivo,
        BigDecimal saldoAnterior,
        BigDecimal saldoNuevo,
        String referencia,
        Instant creadoEn,
        Instant resueltoEn) {
}
