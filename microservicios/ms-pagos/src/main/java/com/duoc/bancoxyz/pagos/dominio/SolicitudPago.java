package com.duoc.bancoxyz.pagos.dominio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Lo que un canal envia para iniciar un pago.
 *
 * <p>Se valida en la frontera y no mas adentro: rechazar un monto negativo aqui
 * cuesta una comparacion, mientras que descubrirlo despues de haber publicado el
 * evento obligaria a compensar una operacion que nunca debio existir.</p>
 *
 * @param referencia identificador que entrega el canal; repetirlo devuelve la
 *                   pago original en lugar de crear una nueva
 */
public record SolicitudPago(

        @NotNull(message = "La cuenta es obligatoria")
        @Positive(message = "El numero de cuenta debe ser positivo")
        Integer cuentaId,

        @NotBlank(message = "El tipo es obligatorio")
        @Pattern(regexp = "DEPOSITO|RETIRO|TRANSFERENCIA|PAGO",
                 message = "El tipo debe ser DEPOSITO, RETIRO, TRANSFERENCIA o PAGO")
        String tipo,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.01", message = "El monto debe ser mayor que cero")
        @Digits(integer = 13, fraction = 2, message = "El monto admite hasta dos decimales")
        BigDecimal monto,

        @NotBlank(message = "La referencia es obligatoria para poder reintentar sin duplicar")
        String referencia,

        // El canal NO lo elige el cuerpo de la peticion: lo pone el BFF que la
        // recibe, que es el unico que sabe de verdad por donde entro. Si lo
        // declarara el llamante, cualquiera podria hacer pasar una operacion de
        // cajero por una de web y esquivar los topes del terminal.
        @NotBlank(message = "El canal es obligatorio")
        @Pattern(regexp = "WEB|MOVIL|CAJERO",
                 message = "El canal debe ser WEB, MOVIL o CAJERO")
        String canal) {
}
