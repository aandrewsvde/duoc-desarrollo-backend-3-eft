package com.duoc.bancoxyz.cuentas.cliente;

/**
 * ms-clientes no pudo responder.
 *
 * <p>Representa solo fallos de infraestructura: conexion rechazada, tiempo
 * agotado, error 5xx. Es la unica que el cortocircuito contabiliza.</p>
 */
public class ClientesNoDisponibleException extends RuntimeException {

    public ClientesNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public ClientesNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
