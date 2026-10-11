package com.duoc.bancoxyz.canal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Cuenta tal como la devuelve ms-cuentas. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CuentaDelCore(
        int numeroCuenta,
        int clienteId,
        String titular,
        String tipoCuenta,
        BigDecimal saldo,
        String moneda,
        String estado) {
}
