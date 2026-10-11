package com.duoc.bancoxyz.eventos;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Segundo paso de la saga por el camino feliz: ms-cuentas aplico el movimiento.
 *
 * <p>Lleva el saldo anterior ademas del nuevo porque un consumidor que llegue
 * tarde -o que reprocese el historico- necesita poder reconstruir que paso, no
 * solo como quedo.</p>
 */
public record SaldoActualizado(
        String eventoId,
        String pagoId,
        int cuentaId,
        BigDecimal saldoAnterior,
        BigDecimal saldoNuevo,
        String moneda,
        // El formato del instante es parte del contrato, no un detalle del
        // serializador. Sin esta anotacion, Jackson escribe un epoch en coma
        // flotante (1791149496.7687683): se deserializa bien, pero es ilegible
        // en la evidencia y en cualquier inspeccion del topico, y pierde la
        // zona horaria. Declararlo aqui, con una anotacion que no arrastra
        // ninguna dependencia de framework, mantiene el modulo libre de Spring.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant ocurridoEn) {
}
