package com.duoc.bancoxyz.cuentas.dominio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Peticion de bloqueo, desbloqueo o cierre de una cuenta.
 *
 * <p>El motivo es obligatorio y no es burocracia: estas tres operaciones son
 * las primeras que pregunta una auditoria, y un registro que dice que una
 * cuenta se cerro sin decir por que no sirve para responderla.</p>
 */
public record SolicitudCambioEstado(

        @NotBlank(message = "El estado es obligatorio")
        @Pattern(regexp = "ACTIVA|BLOQUEADA|CERRADA",
                 message = "El estado debe ser ACTIVA, BLOQUEADA o CERRADA")
        String estado,

        @NotBlank(message = "El motivo es obligatorio para poder auditar el cambio")
        @Size(max = 200, message = "El motivo admite hasta 200 caracteres")
        String motivo) {
}
