package com.duoc.bancoxyz.pagos.cliente;

/**
 * ms-cuentas no pudo responder.
 *
 * <p>Esta excepcion representa <b>solo</b> fallos de infraestructura: conexion
 * rechazada, tiempo agotado, error 5xx. Es la unica que el cortocircuito
 * contabiliza.</p>
 *
 * <p>La distincion es deliberada. Un 404 porque la cuenta no existe significa
 * que ms-cuentas esta perfectamente sano y respondio lo correcto; si contara
 * como fallo, un cliente pidiendo cuentas inexistentes bastaria para abrir el
 * cortocircuito y dejar sin servicio una dependencia que funciona.</p>
 */
public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public ServicioNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
