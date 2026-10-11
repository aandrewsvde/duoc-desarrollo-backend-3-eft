package com.duoc.bancoxyz.clientes.dominio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cliente del banco.
 *
 * @param edad       puede ser {@code null}: el sistema legado la trae vacia en
 *                   parte de los registros y perder al cliente por eso seria
 *                   peor que admitir el hueco
 * @param segmento   ESTANDAR o PREFERENTE, calculado por este servicio
 * @param saldoTotal suma de los saldos de sus cuentas, mantenida por eventos
 */
public record Cliente(
        int id,
        String nombre,
        Integer edad,
        String segmento,
        BigDecimal saldoTotal,
        String estado,
        Instant creadoEn,
        Instant actualizadoEn) {

    public boolean estaActivo() {
        return "ACTIVO".equals(estado);
    }
}
