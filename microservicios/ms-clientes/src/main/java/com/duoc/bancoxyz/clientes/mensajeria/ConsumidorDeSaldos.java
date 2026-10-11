package com.duoc.bancoxyz.clientes.mensajeria;

import com.duoc.bancoxyz.clientes.servicio.CalculadorDeSegmento;
import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.Topicos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Escucha los cambios de saldo para mantener el segmento comercial al dia.
 *
 * <p>Consume el MISMO topico que ms-pagos y ms-notificaciones, pero con su
 * propio grupo de consumidores. Esa es la diferencia practica entre una cola y
 * un registro de eventos: Kafka entrega una copia a cada grupo, de modo que los
 * tres servicios reciben todos los eventos en lugar de repartirselos. Si
 * compartieran grupo, cada evento llegaria solo a uno y dos de los tres
 * quedarian desactualizados sin que nada avisara.</p>
 */
@Component
public class ConsumidorDeSaldos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeSaldos.class);

    private final CalculadorDeSegmento calculador;

    public ConsumidorDeSaldos(CalculadorDeSegmento calculador) {
        this.calculador = calculador;
    }

    /**
     * Procesa un cambio de saldo y confirma la lectura.
     *
     * <p>La confirmacion es manual y va al final, con el recalculo ya hecho.
     * Con confirmacion automatica Kafka daria el evento por consumido apenas lo
     * entrega, y una caida posterior lo perderia sin que nadie lo notara: el
     * segmento quedaria mal calculado para siempre.</p>
     */
    @KafkaListener(topics = Topicos.SALDO_ACTUALIZADO,
                   groupId = "${spring.kafka.consumer.group-id}")
    public void saldoActualizado(SaldoActualizado evento, Acknowledgment confirmacion) {
        log.debug("Recibido saldo de la cuenta {}: {}", evento.cuentaId(), evento.saldoNuevo());

        calculador.aplicarSaldo(evento.eventoId(), Topicos.SALDO_ACTUALIZADO,
                evento.cuentaId(), evento.saldoNuevo(), null);

        confirmacion.acknowledge();
    }
}
