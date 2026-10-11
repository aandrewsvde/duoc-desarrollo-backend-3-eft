package com.duoc.bancoxyz.clientes.dominio;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta o actualizacion de un cliente.
 *
 * <p>No incluye segmento ni saldo: los calcula el servicio. Aceptarlos del
 * exterior permitiria que un canal se auto-asignara la categoria preferente.</p>
 */
public record SolicitudCliente(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre admite hasta 120 caracteres")
        String nombre,

        // La edad es opcional, pero si viene debe ser plausible. El rango
        // replica el que aplica el proceso batch al migrar, para que el dato no
        // dependa de por donde entro.
        @Min(value = 18, message = "La edad minima para ser titular es 18")
        @Max(value = 100, message = "La edad declarada no es plausible")
        Integer edad) {
}
