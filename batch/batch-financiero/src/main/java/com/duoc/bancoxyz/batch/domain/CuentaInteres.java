package com.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;

/** Cuenta con el interes mensual ya aplicado y su saldo final. */
public record CuentaInteres(
        int cuentaId,
        String nombre,
        String tipoCuenta,
        int edad,
        BigDecimal saldoInicial,
        BigDecimal tasaAplicada,
        BigDecimal interesCalculado,
        BigDecimal saldoFinal,
        String periodo) {
}
