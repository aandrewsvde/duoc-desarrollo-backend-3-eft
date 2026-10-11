package com.duoc.bancoxyz.canal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Cliente tal como lo devuelve ms-clientes.
 *
 * <p>Ignora propiedades desconocidas: ms-clientes puede agregar campos sin
 * romper a los tres canales, que es lo que permite desplegarlos por separado.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClienteDelCore(
        int id,
        String nombre,
        Integer edad,
        String segmento,
        BigDecimal saldoTotal,
        String estado) {
}
