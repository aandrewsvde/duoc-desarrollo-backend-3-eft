package com.duoc.bancoxyz.eventos;

/**
 * Nombres de los topicos de Kafka.
 *
 * <p>Estan centralizados porque un topico es un punto de encuentro entre dos
 * servicios distintos: si cada uno escribiera la cadena por su cuenta, una
 * diferencia de una letra haria que el productor publicara en un lugar y el
 * consumidor escuchara en otro, sin ningun error visible. El sistema
 * simplemente dejaria de reaccionar.</p>
 *
 * <p>El prefijo {@code banco.} y la forma {@code <dominio>.<hecho-en-pasado>}
 * no son decorativos: un topico nombra algo que <b>ya ocurrio</b>, no una orden
 * de hacer algo. Esa distincion es la que mantiene desacoplados a los
 * servicios, porque quien publica no sabe ni le importa quien va a reaccionar.</p>
 */
public final class Topicos {

    /** Publica ms-pagos cuando registra un pago en estado PENDIENTE. */
    public static final String PAGOS_REGISTRADOS = "banco.pagos.registrados";

    /** Publica ms-cuentas cuando aplica el movimiento y el saldo queda firme. */
    public static final String SALDO_ACTUALIZADO = "banco.cuentas.saldo-actualizado";

    /** Publica ms-cuentas cuando no puede aplicar el movimiento; dispara la compensacion. */
    public static final String PAGOS_RECHAZADOS = "banco.pagos.rechazados";

    /**
     * Alertas de seguridad de cualquier servicio.
     *
     * <p>Es un topico transversal y no uno por servicio a proposito: quien
     * vigila la seguridad quiere una sola suscripcion, no una por cada
     * componente que pueda emitir una alerta. Agregar manana un servicio nuevo
     * que publique aqui no obliga a tocar al que vigila.</p>
     */
    public static final String ALERTAS_SEGURIDAD = "banco.seguridad.alertas";

    private Topicos() {
    }
}
