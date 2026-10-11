package com.duoc.bancoxyz.batch.domain;

/**
 * Registro que supera la lectura del archivo pero incumple una regla de
 * negocio (monto no positivo, tipo fuera de catalogo, fecha imposible,
 * duplicado, etc.).
 *
 * <p>Es una excepcion <b>omitible y no reintentable</b>: reintentar no
 * cambia el resultado porque el dato de origen es el defectuoso. La
 * politica de omision la deriva a cuarentena y el proceso continua.</p>
 */
public class DatoInvalidoException extends RuntimeException {

    private final String lineaOrigen;

    public DatoInvalidoException(String motivo, String lineaOrigen) {
        super(motivo);
        this.lineaOrigen = lineaOrigen;
    }

    public String getLineaOrigen() {
        return lineaOrigen;
    }
}
