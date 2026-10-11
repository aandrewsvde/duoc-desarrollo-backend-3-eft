package com.duoc.bancoxyz.pagos.dominio;

/**
 * Estados por los que pasa un pago.
 *
 * <p>No hay un estado "FALLIDA" separado: un pago que ms-cuentas
 * rechaza se COMPENSA, que es lo que la saga hace realmente. Nombrar el estado
 * por la accion de reversa y no por el fallo deja explicito que el sistema
 * volvio a un estado consistente y no que quedo roto.</p>
 */
public enum EstadoPago {

    /** Registrada y publicada; el saldo todavia no se movio. */
    PENDIENTE,

    /** ms-cuentas aplico el cambio e informo el saldo resultante. */
    CONFIRMADA,

    /** ms-cuentas la rechazo y se revirtio con su motivo. */
    COMPENSADA
}
