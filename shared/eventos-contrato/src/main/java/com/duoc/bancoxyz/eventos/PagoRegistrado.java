package com.duoc.bancoxyz.eventos;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un pago quedo registrado y espera que se aplique sobre el saldo.
 *
 * <p>Es el hecho que inicia la saga. ms-pagos no descuenta dinero: anota la
 * intencion y publica este evento. Quien decide si el movimiento es posible es
 * ms-cuentas, que es el unico dueno del saldo.</p>
 *
 * @param eventoId   identificador del EVENTO, no de la operacion. Es lo que
 *                   permite al consumidor descartar una reentrega sin volver a
 *                   aplicar el movimiento
 * @param pagoId     identificador de la operacion de negocio
 * @param cuentaId   cuenta sobre la que se opera
 * @param tipo       DEPOSITO, RETIRO, TRANSFERENCIA o PAGO
 * @param monto      importe, siempre positivo: el signo lo decide el tipo
 * @param ocurridoEn instante en que el hecho sucedio, no en que se publico
 */
public record PagoRegistrado(
        String eventoId,
        String pagoId,
        int cuentaId,
        String tipo,
        BigDecimal monto,
        String moneda,
        String canal,
        // El formato del instante es parte del contrato, no un detalle del
        // serializador. Sin esta anotacion Jackson escribe un epoch en coma
        // flotante: se deserializa bien, pero es ilegible al inspeccionar el
        // topico y pierde la zona horaria.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant ocurridoEn) {
}
