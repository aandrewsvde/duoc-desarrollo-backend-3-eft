package com.duoc.bancoxyz.cuentas.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Palanca para inducir lentitud y poder demostrar la resiliencia.
 *
 * <p>Existe por una razon practica: el cortocircuito y el limite de tiempo de
 * ms-pagos solo se pueden evidenciar si este servicio responde mal, y
 * detener el contenedor demuestra la caida total pero no la degradacion, que es
 * el caso mas interesante -el servicio responde, pero tarde-. Esta palanca lo
 * reproduce de forma controlada y repetible.</p>
 *
 * <p>Es un elemento de prueba, no de negocio, y por eso esta detras de una
 * propiedad: con {@code banco.simulacion.habilitada=false} el bean ni siquiera
 * se crea y la ruta no existe. En un despliegue real esa propiedad vendria en
 * falso.</p>
 */
@RestController
@RequestMapping("/api/simulacion")
@ConditionalOnProperty(name = "banco.simulacion.habilitada", havingValue = "true")
@Tag(name = "Simulacion", description = "Induce degradacion controlada para evidenciar la resiliencia")
public class SimulacionControlador {

    private static final Logger log = LoggerFactory.getLogger(SimulacionControlador.class);

    /** Retardo artificial aplicado a cada consulta, en milisegundos. */
    public static final AtomicLong RETARDO_MS = new AtomicLong(0);

    @PostMapping("/latencia")
    @Operation(summary = "Fija el retardo artificial de las consultas")
    public Map<String, Object> fijarLatencia(@RequestParam long milisegundos) {
        long anterior = RETARDO_MS.getAndSet(Math.max(0, milisegundos));
        log.warn("Retardo artificial cambiado de {} ms a {} ms", anterior, milisegundos);
        return Map.of("retardoAnteriorMs", anterior, "retardoActualMs", RETARDO_MS.get());
    }
}
