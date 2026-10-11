package com.duoc.bancoxyz.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Validacion del token en la frontera.
 *
 * <p>La configuracion es reactiva ({@code ServerHttpSecurity}) y no la de
 * servlet, porque Gateway corre sobre WebFlux. Usar la otra compilaria y no
 * haria nada: los filtros nunca llegarian a aplicarse.</p>
 *
 * <p>El gateway rechaza lo que no trae token valido, de modo que el trafico sin
 * credenciales no llega siquiera a la red interna. Los microservicios vuelven a
 * comprobar el alcance por su cuenta: el gateway responde "quien eres" y cada
 * servicio responde "que puedes hacer aqui".</p>
 */
@Configuration
@EnableWebFluxSecurity
public class ConfiguracionSeguridad {

    @Bean
    public SecurityWebFilterChain cadenaDeFiltros(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(rutas -> rutas
                        .pathMatchers("/actuator/**").permitAll()
                        // Las preticiones de verificacion previa del navegador
                        // viajan sin cabecera de autorizacion por definicion; si
                        // se exigiera token, ninguna llamada desde un navegador
                        // de otro origen llegaria a hacerse.
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(servidor -> servidor.jwt(Customizer.withDefaults()))
                .build();
    }
}
