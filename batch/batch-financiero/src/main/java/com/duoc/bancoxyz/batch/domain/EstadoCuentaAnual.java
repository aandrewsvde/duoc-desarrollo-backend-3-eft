package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Estado de cuenta anual compilado, tal como se emite para auditoria. */
public record EstadoCuentaAnual(
        int cuentaId,
        int anio,
        int totalMovimientos,
        int totalDepositos,
        int totalRetiros,
        int totalCompras,
        int totalPagos,
        BigDecimal montoAbonos,
        BigDecimal montoCargos,
        BigDecimal saldoNeto,
        LocalDate primerMovimiento,
        LocalDate ultimoMovimiento) {
}
