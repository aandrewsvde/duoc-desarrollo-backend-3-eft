package com.duoc.bancoxyz.cuentas.mensajeria;

import com.duoc.bancoxyz.cuentas.dominio.ResultadoAplicacion;
import com.duoc.bancoxyz.cuentas.servicio.AplicadorDePagos;
import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.Topicos;
import com.duoc.bancoxyz.eventos.PagoRegistrado;
import com.duoc.bancoxyz.eventos.PagoRechazado;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del topico de pagos creadas.
 *
 * <p>Este es el punto donde la comunicacion deja de ser sincrona. ms-pagos
 * no espera una respuesta: publica el hecho y sigue. Si ms-cuentas esta caido, el
 * evento queda en el topico y se procesa cuando vuelva, sin que el usuario haya
 * visto un error. Ese desacople temporal es la ventaja concreta de la mensajeria
 * frente a una llamada HTTP, que habria fallado en el acto.</p>
 */
@Component
public class ConsumidorDePagos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDePagos.class);

    private final AplicadorDePagos aplicador;
    private final PublicadorDeEventos publicador;

    public ConsumidorDePagos(AplicadorDePagos aplicador, PublicadorDeEventos publicador) {
        this.aplicador = aplicador;
        this.publicador = publicador;
    }

    /**
     * Procesa un evento y confirma su lectura.
     *
     * <p>La confirmacion es manual y se hace al final, ya aplicado el cambio. Con
     * confirmacion automatica, Kafka daria el evento por consumido apenas lo
     * entrega y una caida posterior lo perderia; aqui, si el proceso muere antes
     * del {@code acknowledge}, el evento se vuelve a entregar. Ese reintento es
     * seguro porque la aplicacion es idempotente.</p>
     *
     * <p>Si la aplicacion lanza, deliberadamente <b>no</b> se confirma: la
     * excepcion sube al contenedor, que reintenta y, agotados los intentos,
     * deriva el mensaje segun la politica configurada. Tragarse el error aqui
     * dejaria un pago pendiente para siempre, sin confirmar ni compensar.</p>
     */
    @KafkaListener(
            topics = Topicos.PAGOS_REGISTRADOS,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void recibir(PagoRegistrado evento,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int particion,
                        @Header(KafkaHeaders.OFFSET) long desplazamiento,
                        Acknowledgment confirmacion) {

        log.info("Recibido {} desde {} (particion {}, offset {})",
                evento.pagoId(), Topicos.PAGOS_REGISTRADOS, particion, desplazamiento);

        ResultadoAplicacion resultado = aplicador.aplicar(evento);

        switch (resultado) {
            case ResultadoAplicacion.Aplicada aplicada -> publicador.publicar(
                    Topicos.SALDO_ACTUALIZADO,
                    evento.pagoId(),
                    new SaldoActualizado(
                            UUID.randomUUID().toString(),
                            evento.pagoId(),
                            evento.cuentaId(),
                            aplicada.saldoAnterior(),
                            aplicada.saldoNuevo(),
                            aplicada.moneda(),
                            Instant.now()));

            case ResultadoAplicacion.Rechazada rechazada -> publicador.publicar(
                    Topicos.PAGOS_RECHAZADOS,
                    evento.pagoId(),
                    new PagoRechazado(
                            UUID.randomUUID().toString(),
                            evento.pagoId(),
                            evento.cuentaId(),
                            rechazada.motivo(),
                            Instant.now()));

            // Un duplicado no vuelve a notificarse: el evento de resultado ya se
            // emitio la primera vez y repetirlo haria que ms-notificaciones
            // avisara dos veces del mismo movimiento.
            case ResultadoAplicacion.Duplicada duplicada ->
                    log.debug("Sin eventos de salida para el duplicado {}", duplicada.eventoId());
        }

        confirmacion.acknowledge();
    }
}
