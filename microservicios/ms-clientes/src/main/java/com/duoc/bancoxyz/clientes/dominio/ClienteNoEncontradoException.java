package com.duoc.bancoxyz.clientes.dominio;

/** No existe un cliente con ese identificador. */
public class ClienteNoEncontradoException extends RuntimeException {

    public ClienteNoEncontradoException(int id) {
        super("No existe el cliente " + id);
    }
}
