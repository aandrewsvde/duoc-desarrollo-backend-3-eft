package com.duoc.bancoxyz.cuentas.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Aplica el retardo artificial configurado por {@link SimulacionControlador}.
 *
 * <p>Se excluye a si mismo y a actuator: si la palanca que apaga la lentitud
 * quedara afectada por la lentitud, no habria forma de volver al estado normal
 * sin reiniciar; y si el healthcheck se retrasara, Docker declararia enfermo un
 * contenedor que esta vivo.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "banco.simulacion.habilitada", havingValue = "true")
public class FiltroDeRetardo extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {
        long retardo = SimulacionControlador.RETARDO_MS.get();
        String ruta = peticion.getRequestURI();
        if (retardo > 0 && !ruta.startsWith("/actuator") && !ruta.startsWith("/api/simulacion")) {
            try {
                Thread.sleep(retardo);
            } catch (InterruptedException interrumpido) {
                Thread.currentThread().interrupt();
            }
        }
        cadena.doFilter(peticion, respuesta);
    }
}
