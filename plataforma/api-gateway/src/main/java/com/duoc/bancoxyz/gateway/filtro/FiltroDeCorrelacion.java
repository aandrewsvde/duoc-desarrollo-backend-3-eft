package com.duoc.bancoxyz.gateway.filtro;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Marca cada peticion con un identificador de correlacion.
 *
 * <p>Resuelve un problema propio de los sistemas distribuidos: una sola accion
 * del usuario deja rastro en cuatro servicios distintos y, sin un hilo comun,
 * reconstruir que paso obliga a cruzar registros por hora aproximada. Con este
 * identificador, un {@code grep} basta para ver el recorrido completo.</p>
 *
 * <p>Si el cliente ya trae uno, se respeta: asi la traza puede empezar antes del
 * gateway, por ejemplo en una aplicacion movil.</p>
 */
@Component
public class FiltroDeCorrelacion implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FiltroDeCorrelacion.class);

    public static final String CABECERA = "X-Correlacion-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, GatewayFilterChain cadena) {
        ServerHttpRequest original = intercambio.getRequest();
        String correlacion = original.getHeaders().getFirst(CABECERA);
        if (correlacion == null || correlacion.isBlank()) {
            correlacion = UUID.randomUUID().toString().substring(0, 8);
        }

        final String id = correlacion;
        ServerHttpRequest marcada = original.mutate().header(CABECERA, id).build();

        log.info("[{}] {} {} -> enrutando", id, original.getMethod(), original.getURI().getPath());

        return cadena.filter(intercambio.mutate().request(marcada).build())
                .doOnSuccess(sinValor -> log.info("[{}] respuesta {}", id,
                        intercambio.getResponse().getStatusCode()))
                // La respuesta tambien lo lleva: asi quien reporta un problema
                // puede entregar el identificador exacto de su peticion.
                .doFirst(() -> intercambio.getResponse().getHeaders().add(CABECERA, id));
    }

    /**
     * Se ejecuta antes que cualquier otro filtro para que la cabecera exista
     * desde el primer registro; si corriera al final, las lineas iniciales
     * quedarian sin identificador y el rastro empezaria a la mitad.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
