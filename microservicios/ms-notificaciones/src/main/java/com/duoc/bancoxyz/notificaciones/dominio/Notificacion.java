package com.duoc.bancoxyz.notificaciones.dominio;

import java.time.Instant;

/**
 * Aviso generado a partir de un evento.
 *
 * @param canal   medio por el que se habria enviado en un sistema real
 * @param nivel   INFORMATIVA o ALERTA, segun el desenlace de el pago
 * @param mensaje texto tal como lo leeria el titular
 */
public record Notificacion(
        String id,
        int cuentaId,
        String pagoId,
        String canal,
        String nivel,
        String mensaje,
        Instant generadaEn) {
}
