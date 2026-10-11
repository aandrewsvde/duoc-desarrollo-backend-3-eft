package com.duoc.bancoxyz.eventos;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Camino alternativo de la saga: ms-cuentas no pudo aplicar el movimiento.
 *
 * <p>Este evento es el que dispara la <b>compensacion</b>. En una saga no hay
 * transaccion distribuida que revertir: cada servicio ya confirmo su parte
 * localmente, de modo que deshacer significa ejecutar una accion contraria.
 * Aqui, ms-pagos pasa el pago de PENDIENTE a COMPENSADO.</p>
 *
 * @param motivo razon de negocio del rechazo, legible y especifica: es lo que
 *               el canal le muestra al titular y lo que queda en la bitacora
 */
public record PagoRechazado(
        String eventoId,
        String pagoId,
        int cuentaId,
        String motivo,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant ocurridoEn) {
}
