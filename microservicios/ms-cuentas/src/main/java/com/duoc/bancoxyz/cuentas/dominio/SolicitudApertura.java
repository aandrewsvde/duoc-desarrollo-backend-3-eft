package com.duoc.bancoxyz.cuentas.dominio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Peticion de apertura de una cuenta.
 *
 * <p>No incluye el numero de cuenta: lo asigna el servicio. Dejar que el
 * cliente lo eligiera abriria la puerta a que dos canales pidieran el mismo
 * numero, y a que alguien pudiera adivinar o reclamar un numero ajeno.</p>
 *
 * @param montoInicial deposito de apertura; cero es valido, negativo no
 */
public record SolicitudApertura(

        @NotNull(message = "El cliente es obligatorio")
        @Positive(message = "El identificador de cliente debe ser positivo")
        Integer clienteId,

        @NotNull(message = "El tipo de cuenta es obligatorio")
        @Pattern(regexp = "AHORRO|PRESTAMO|HIPOTECA",
                 message = "El tipo debe ser AHORRO, PRESTAMO o HIPOTECA")
        String tipoCuenta,

        @NotNull(message = "El monto inicial es obligatorio")
        @DecimalMin(value = "0.00", message = "El monto inicial no puede ser negativo")
        BigDecimal montoInicial) {
}
