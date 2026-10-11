package com.duoc.bancoxyz.cuentas.dominio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cuenta bancaria tal como la ve el exterior.
 *
 * @param numeroCuenta  identificador de negocio, heredado del sistema legado
 * @param clienteId     titular en ms-clientes; aqui es solo un identificador,
 *                      no una clave foranea, porque esa tabla vive en otra base
 * @param titular       nombre del titular, copiado de ms-clientes al abrir la
 *                      cuenta para evitar una llamada de red en cada consulta
 * @param tipoCuenta    AHORRO, PRESTAMO o HIPOTECA
 * @param saldo         saldo vigente
 * @param moneda        codigo ISO de la moneda
 * @param estado        ACTIVA, BLOQUEADA o CERRADA
 * @param abiertaEn     apertura de la cuenta
 * @param cerradaEn     cierre, o {@code null} si sigue abierta
 * @param actualizadaEn ultima modificacion del saldo o del estado
 */
public record Cuenta(
        int numeroCuenta,
        int clienteId,
        String titular,
        String tipoCuenta,
        BigDecimal saldo,
        String moneda,
        String estado,
        Instant abiertaEn,
        Instant cerradaEn,
        Instant actualizadaEn) {

    /** Solo una cuenta ACTIVA admite movimientos. */
    public boolean admiteMovimientos() {
        return "ACTIVA".equals(estado);
    }
}
