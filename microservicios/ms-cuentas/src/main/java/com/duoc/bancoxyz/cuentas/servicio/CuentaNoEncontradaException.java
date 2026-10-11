package com.duoc.bancoxyz.cuentas.servicio;

/** La cuenta pedida no existe en este microservicio. */
public class CuentaNoEncontradaException extends RuntimeException {

    private final int numeroCuenta;

    public CuentaNoEncontradaException(int numeroCuenta) {
        super("No existe la cuenta " + numeroCuenta);
        this.numeroCuenta = numeroCuenta;
    }

    public int getNumeroCuenta() {
        return numeroCuenta;
    }
}
