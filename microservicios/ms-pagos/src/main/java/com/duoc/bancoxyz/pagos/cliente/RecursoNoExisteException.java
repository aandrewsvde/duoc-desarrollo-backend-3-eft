package com.duoc.bancoxyz.pagos.cliente;

/** ms-cuentas respondio que la cuenta no existe: no es un fallo del servicio. */
public class RecursoNoExisteException extends RuntimeException {

    public RecursoNoExisteException(int cuentaId) {
        super("La cuenta " + cuentaId + " no existe en ms-cuentas");
    }
}
