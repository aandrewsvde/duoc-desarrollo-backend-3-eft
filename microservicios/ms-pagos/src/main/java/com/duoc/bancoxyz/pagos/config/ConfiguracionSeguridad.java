package com.duoc.bancoxyz.pagos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Este microservicio como servidor de recursos OAuth 2.0.
 *
 * <p>Separa lectura de escritura: consultar exige {@code pagos.leer} y
 * crear exige {@code pagos.escribir}. Es una diferencia con
 * consecuencias reales: el token de servicio que usan los procesos automaticos
 * solo pide los alcances de lectura, de modo que aunque se filtrara no podria
 * mover dinero.</p>
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain cadenaDeFiltros(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rutas -> rutas
                    .requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/pagos/**")
                        .hasAuthority("SCOPE_pagos.escribir")
                    .requestMatchers(HttpMethod.GET, "/api/pagos/**")
                        .hasAuthority("SCOPE_pagos.leer")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(servidor -> servidor.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
