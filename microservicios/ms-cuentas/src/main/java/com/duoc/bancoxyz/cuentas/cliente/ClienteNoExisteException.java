package com.duoc.bancoxyz.cuentas.cliente;

/**
 * ms-clientes respondio que el cliente no existe.
 *
 * <p>No es un fallo del servicio: es una respuesta correcta a una pregunta
 * sobre algo que no esta. Por eso queda fuera de lo que cuenta el
 * cortocircuito; si contara, pedir clientes inexistentes bastaria para dejar
 * sin servicio una dependencia sana.</p>
 */
public class ClienteNoExisteException extends RuntimeException {

    public ClienteNoExisteException(int clienteId) {
        super("El cliente " + clienteId + " no existe en ms-clientes");
    }
}
