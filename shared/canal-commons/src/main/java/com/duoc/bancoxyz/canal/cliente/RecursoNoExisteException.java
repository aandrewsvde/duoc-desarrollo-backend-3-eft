package com.duoc.bancoxyz.canal.cliente;

/**
 * El microservicio respondio que el recurso pedido no existe.
 *
 * <p>No es un fallo suyo: es una respuesta correcta. Por eso queda fuera de lo
 * que cuenta el cortocircuito; si contara, un cliente pidiendo cuentas
 * inexistentes bastaria para dejar sin servicio al canal entero.</p>
 */
public class RecursoNoExisteException extends RuntimeException {

    public RecursoNoExisteException(String mensaje) {
        super(mensaje);
    }
}
