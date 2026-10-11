package com.duoc.bancoxyz.pagos.cliente;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Lo poco que ms-pagos necesita saber de una cuenta.
 *
 * <p>Ignora las propiedades desconocidas a proposito: asi ms-cuentas puede
 * agregar campos a su respuesta sin romper a este servicio, que es lo que
 * permite desplegarlos por separado.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumenCuenta(
        int numeroCuenta,
        String titular,
        BigDecimal saldo,
        String moneda,
        String estado) {
}
