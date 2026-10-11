package com.duoc.bancoxyz.cuentas.mensajeria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publicacion de los eventos de resultado de ms-cuentas. */
@Component
public class PublicadorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDeEventos.class);

    private final KafkaTemplate<String, Object> plantilla;

    public PublicadorDeEventos(KafkaTemplate<String, Object> plantilla) {
        this.plantilla = plantilla;
    }

    /**
     * Publica con el identificador de pago como clave.
     *
     * <p>La clave determina la particion, de modo que todos los eventos de una
     * misma pago caen en la misma y se consumen en orden. Sin clave, el
     * rechazo podria procesarse antes que la creacion y la compensacion llegaria
     * a destiempo.</p>
     */
    public void publicar(String topico, String clave, Object evento) {
        plantilla.send(topico, clave, evento)
                .whenComplete((resultado, fallo) -> {
                    if (fallo != null) {
                        log.error("No se pudo publicar en {} la clave {}: {}",
                                topico, clave, fallo.getMessage());
                    } else {
                        log.info("Publicado en {} (particion {}, offset {}): {}",
                                topico,
                                resultado.getRecordMetadata().partition(),
                                resultado.getRecordMetadata().offset(),
                                evento);
                    }
                });
    }
}
