package com.duoc.bancoxyz.cuentas.dominio;

import java.math.BigDecimal;

/**
 * Desenlace de aplicar un pago sobre una cuenta.
 *
 * <p>Modelar el rechazo como un valor de retorno, y no como una excepcion, es
 * intencional: que una cuenta no tenga saldo no es un fallo del sistema sino un
 * resultado de negocio previsto, y debe viajar de vuelta como evento igual que
 * el exito. Las excepciones quedan reservadas para lo que si es una averia
 * (la base caida, por ejemplo), que es lo unico que conviene reintentar.</p>
 */
public sealed interface ResultadoAplicacion {

    /** El pago se aplico y el saldo cambio. */
    record Aplicada(BigDecimal saldoAnterior, BigDecimal saldoNuevo, String moneda)
            implements ResultadoAplicacion {}

    /** El pago no se aplico por una regla de negocio. */
    record Rechazada(String motivo) implements ResultadoAplicacion {}

    /** El evento ya habia sido procesado; no se vuelve a aplicar ni a notificar. */
    record Duplicada(String eventoId) implements ResultadoAplicacion {}
}
