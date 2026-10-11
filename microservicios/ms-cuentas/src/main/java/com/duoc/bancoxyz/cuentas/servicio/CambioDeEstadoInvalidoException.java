package com.duoc.bancoxyz.cuentas.servicio;

/**
 * El cambio de estado solicitado no es posible.
 *
 * <p>Es un resultado de negocio, no una averia: reintentarlo no cambiaria nada,
 * de modo que debe llegar al solicitante como 409 y no como 500.</p>
 */
public class CambioDeEstadoInvalidoException extends RuntimeException {

    public CambioDeEstadoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
