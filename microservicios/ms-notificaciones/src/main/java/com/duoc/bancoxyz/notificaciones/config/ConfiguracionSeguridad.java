package com.duoc.bancoxyz.notificaciones.config;

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
 * <p>Solo expone lectura y la protege con {@code pagos.leer}: quien
 * puede ver el detalle de un pago puede ver tambien el aviso que
 * genero. No se definio un alcance propio para las notificaciones porque seria
 * un permiso sin contenido distinto que custodiar.</p>
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
                    .requestMatchers(HttpMethod.GET, "/api/notificaciones/**")
                        .hasAuthority("SCOPE_pagos.leer")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(servidor -> servidor.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
