package com.duoc.bancoxyz.bff.web.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Lo que el navegador envia para iniciar un pago.
 *
 * <p>No incluye el canal: lo pone el BFF. Es la diferencia entre un dato que el
 * sistema conoce y uno que el cliente declara, y en seguridad esa diferencia lo
 * es todo.</p>
 */
public record SolicitudPagoWeb(

        @NotNull(message = "La cuenta es obligatoria")
        @Positive(message = "El numero de cuenta debe ser positivo")
        Integer cuentaId,

        @NotBlank(message = "El tipo es obligatorio")
        @Pattern(regexp = "DEPOSITO|RETIRO|TRANSFERENCIA|PAGO",
                 message = "El tipo debe ser DEPOSITO, RETIRO, TRANSFERENCIA o PAGO")
        String tipo,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.01", message = "El monto debe ser mayor que cero")
        BigDecimal monto,

        @NotBlank(message = "La referencia es obligatoria para poder reintentar sin duplicar")
        String referencia) {
}
