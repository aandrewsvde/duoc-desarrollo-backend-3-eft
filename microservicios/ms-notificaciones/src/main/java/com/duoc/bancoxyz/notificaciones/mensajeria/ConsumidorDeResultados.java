package com.duoc.bancoxyz.notificaciones.mensajeria;

import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.Topicos;
import com.duoc.bancoxyz.eventos.PagoRechazado;
import com.duoc.bancoxyz.notificaciones.dominio.Notificacion;
import com.duoc.bancoxyz.notificaciones.servicio.BandejaDeNotificaciones;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Convierte en avisos los dos desenlaces de la saga.
 *
 * <p>Este servicio consume los MISMOS topicos que ms-pagos, pero con su
 * propio grupo de consumidores. Esa es la diferencia practica entre una cola y
 * un registro de eventos: Kafka entrega una copia a cada grupo, de modo que los
 * dos servicios reciben todos los eventos en lugar de repartirselos. Si
 * compartieran grupo, cada evento llegaria solo a uno de los dos y la mitad de
 * las pagos quedarian sin resolver o sin notificar.</p>
 */
@Component
public class ConsumidorDeResultados {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeResultados.class);

    private final BandejaDeNotificaciones bandeja;

    public ConsumidorDeResultados(BandejaDeNotificaciones bandeja) {
        this.bandeja = bandeja;
    }

    @KafkaListener(topics = Topicos.SALDO_ACTUALIZADO,
                   groupId = "${spring.kafka.consumer.group-id}")
    public void saldoActualizado(SaldoActualizado evento, Acknowledgment confirmacion) {
        String mensaje = ("Banco XYZ: se registro un movimiento en su cuenta %d. "
                + "Saldo anterior %s %s, saldo actual %s %s. Pago %s.")
                .formatted(evento.cuentaId(),
                        evento.saldoAnterior().toPlainString(), evento.moneda(),
                        evento.saldoNuevo().toPlainString(), evento.moneda(),
                        evento.pagoId());

        Notificacion notificacion = new Notificacion(
                UUID.randomUUID().toString(), evento.cuentaId(), evento.pagoId(),
                "CORREO", "INFORMATIVA", mensaje, Instant.now());

        bandeja.agregar(notificacion);
        log.info("[{}] {}", notificacion.canal(), mensaje);
        confirmacion.acknowledge();
    }

    /**
     * Un rechazo se notifica como ALERTA y por un canal mas inmediato.
     *
     * <p>No es cosmetico: un aviso de saldo se puede leer mas tarde, pero una
     * operacion que no se completo exige que el titular se entere ahora, porque
     * probablemente este esperando frente a un cajero o una caja.</p>
     */
    @KafkaListener(topics = Topicos.PAGOS_RECHAZADOS,
                   groupId = "${spring.kafka.consumer.group-id}")
    public void pagoRechazado(PagoRechazado evento, Acknowledgment confirmacion) {
        String mensaje = ("Banco XYZ: no pudimos completar el pago %s sobre su cuenta %d. "
                + "Motivo: %s.")
                .formatted(evento.pagoId(), evento.cuentaId(), evento.motivo());

        Notificacion notificacion = new Notificacion(
                UUID.randomUUID().toString(), evento.cuentaId(), evento.pagoId(),
                "SMS", "ALERTA", mensaje, Instant.now());

        bandeja.agregar(notificacion);
        log.warn("[{}] {}", notificacion.canal(), mensaje);
        confirmacion.acknowledge();
    }
}
