package com.duoc.bancoxyz.pagos.mensajeria;

import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.Topicos;
import com.duoc.bancoxyz.eventos.PagoRechazado;
import com.duoc.bancoxyz.pagos.servicio.ResolutorDeSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Escucha los dos desenlaces posibles que publica ms-cuentas.
 *
 * <p>Que sean dos topicos y no uno con un campo "exito" es deliberado:
 * ms-notificaciones consume ambos pero con mensajes distintos, y un consumidor
 * futuro que solo se interese por los rechazos -un tablero de fraude, por
 * ejemplo- puede suscribirse unicamente a ese topico sin filtrar el resto del
 * trafico.</p>
 */
@Component
public class ConsumidorResultados {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorResultados.class);

    private final ResolutorDeSaga resolutor;

    public ConsumidorResultados(ResolutorDeSaga resolutor) {
        this.resolutor = resolutor;
    }

    @KafkaListener(topics = Topicos.SALDO_ACTUALIZADO,
                   groupId = "${spring.kafka.consumer.group-id}")
    public void saldoActualizado(SaldoActualizado evento, Acknowledgment confirmacion) {
        log.info("Recibida confirmacion de saldo para {}", evento.pagoId());
        resolutor.confirmar(evento, Topicos.SALDO_ACTUALIZADO);
        confirmacion.acknowledge();
    }

    @KafkaListener(topics = Topicos.PAGOS_RECHAZADOS,
                   groupId = "${spring.kafka.consumer.group-id}")
    public void pagoRechazada(PagoRechazado evento, Acknowledgment confirmacion) {
        log.info("Recibido rechazo para {}: {}", evento.pagoId(), evento.motivo());
        resolutor.compensar(evento, Topicos.PAGOS_RECHAZADOS);
        confirmacion.acknowledge();
    }
}
