package com.duoc.bancoxyz.clientes.servicio;

/**
 * La operacion no es posible en el estado actual del cliente.
 *
 * <p>Es un conflicto de negocio, no una averia: reintentarlo daria el mismo
 * resultado, de modo que corresponde un 409 y no un 500.</p>
 */
public class EstadoClienteInvalidoException extends RuntimeException {

    public EstadoClienteInvalidoException(String mensaje) {
        super(mensaje);
    }
}
