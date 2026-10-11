package com.duoc.bancoxyz.canal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Movimiento tal como lo devuelve ms-cuentas. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovimientoDelCore(
        long id,
        int cuentaId,
        LocalDate fecha,
        String tipo,
        int signo,
        BigDecimal monto,
        String descripcion,
        String origen) {

    /** Importe con su signo contable aplicado, que es como se muestra. */
    public BigDecimal montoConSigno() {
        return monto.multiply(BigDecimal.valueOf(signo));
    }
}
