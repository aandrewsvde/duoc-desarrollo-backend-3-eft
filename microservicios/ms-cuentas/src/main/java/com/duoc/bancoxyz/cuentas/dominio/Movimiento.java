package com.duoc.bancoxyz.cuentas.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Movimiento contable de una cuenta.
 *
 * <p>El signo viene resuelto desde el ingreso del dato (+1 abona, -1 carga) en
 * lugar de deducirse del tipo en cada consulta: la regla se aplica una vez y
 * todas las lecturas posteriores coinciden por construccion.</p>
 */
public record Movimiento(
        long id,
        int cuentaId,
        String pagoId,
        LocalDate fecha,
        String tipo,
        int signo,
        BigDecimal monto,
        String descripcion,
        String origen) {
}
