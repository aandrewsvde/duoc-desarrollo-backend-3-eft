package com.duoc.bancoxyz.canal.cliente;

/**
 * Un microservicio del core no pudo responder.
 *
 * <p>Representa solo fallos de infraestructura: conexion rechazada, tiempo
 * agotado, error 5xx. Es la unica que el cortocircuito del canal contabiliza,
 * porque es la unica que indica que el otro lado esta averiado.</p>
 */
public class CoreNoDisponibleException extends RuntimeException {

    public CoreNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public CoreNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
