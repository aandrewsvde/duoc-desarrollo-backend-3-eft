package com.duoc.bancoxyz.cuentas.dominio;

import java.time.Instant;

/** Una entrada de la bitacora de cambios de estado de una cuenta. */
public record CambioDeEstado(
        long id,
        int cuentaId,
        String estadoPrevio,
        String estadoNuevo,
        String motivo,
        String solicitadoPor,
        Instant ocurridoEn) {
}
