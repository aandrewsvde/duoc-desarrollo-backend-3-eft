package com.duoc.bancoxyz.pagos.servicio;

/** No existe un pago con ese identificador. */
public class PagoNoEncontradoException extends RuntimeException {

    public PagoNoEncontradoException(String id) {
        super("No existe el pago " + id);
    }
}
