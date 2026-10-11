package com.duoc.bancoxyz.pagos.mensajeria;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/** Publicacion de los eventos que origina ms-pagos. */
@Component
public class PublicadorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDeEventos.class);

    /** Techo de espera al confirmar la publicacion, para no bloquear la peticion HTTP. */
    private static final long ESPERA_SEGUNDOS = 10;

    private final KafkaTemplate<String, Object> plantilla;

    public PublicadorDeEventos(KafkaTemplate<String, Object> plantilla) {
        this.plantilla = plantilla;
    }

    /**
     * Publica y espera la confirmacion del broker.
     *
     * <p>Esperar es lo que permite que la publicacion participe de la
     * pago de base de datos: si se enviara sin esperar, el metodo
     * terminaria, el pago confirmaria y un fallo posterior del envio ya
     * no podria revertir nada.</p>
     *
     * <p>La clave es el identificador de el pago: fija la particion, de
     * modo que todos los eventos de una misma pago se consumen en el
     * orden en que se produjeron.</p>
     */
    public void publicarYEsperar(String topico, String clave, Object evento) {
        try {
            SendResult<String, Object> resultado =
                    plantilla.send(topico, clave, evento).get(ESPERA_SEGUNDOS, TimeUnit.SECONDS);
            log.info("Publicado en {} (particion {}, offset {}): {}",
                    topico,
                    resultado.getRecordMetadata().partition(),
                    resultado.getRecordMetadata().offset(),
                    evento);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new PublicacionFallidaException(topico, interrumpido);
        } catch (ExecutionException | TimeoutException fallo) {
            throw new PublicacionFallidaException(topico, fallo);
        }
    }

    /** No se pudo dejar el evento en el broker; la operacion completa se revierte. */
    public static class PublicacionFallidaException extends RuntimeException {
        public PublicacionFallidaException(String topico, Throwable causa) {
            super("No se pudo publicar el evento en " + topico + ": " + causa.getMessage(), causa);
        }
    }
}
