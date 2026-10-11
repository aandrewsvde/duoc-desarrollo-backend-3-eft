package com.duoc.bancoxyz.bff.cajero.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Orden de retiro que envia un terminal.
 *
 * @param terminal   identificador del cajero; queda en la referencia para poder
 *                   rastrear la operacion hasta el equipo fisico
 * @param referencia identificador unico de la orden que genera el terminal. Es
 *                   lo que hace idempotente el retiro: si el cajero reenvia la
 *                   misma orden tras un corte de red, el dinero sale una vez
 */
public record SolicitudRetiro(

        @NotNull(message = "La cuenta es obligatoria")
        @Positive(message = "El numero de cuenta debe ser positivo")
        Integer cuentaId,

        @NotNull(message = "El monto es obligatorio")
        @Positive(message = "El monto debe ser mayor que cero")
        BigDecimal monto,

        @NotBlank(message = "El terminal es obligatorio")
        String terminal,

        @NotBlank(message = "La referencia es obligatoria para poder reintentar sin duplicar")
        String referencia) {
}
