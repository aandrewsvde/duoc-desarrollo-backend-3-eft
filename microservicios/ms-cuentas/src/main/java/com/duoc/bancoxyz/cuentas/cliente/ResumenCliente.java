package com.duoc.bancoxyz.cuentas.cliente;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Lo poco que ms-cuentas necesita saber de un cliente.
 *
 * <p>Ignora las propiedades desconocidas a proposito: asi ms-clientes puede
 * agregar campos a su respuesta sin romper a este servicio, que es lo que
 * permite desplegarlos por separado.</p>
 *
 * <p>No incluye edad, direccion ni ningun otro dato personal. Pedir solo lo
 * que se va a usar limita el dano si este servicio o sus registros se
 * comprometen.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumenCliente(
        int id,
        String nombre,
        String estado) {

    public boolean estaActivo() {
        return "ACTIVO".equals(estado);
    }
}
