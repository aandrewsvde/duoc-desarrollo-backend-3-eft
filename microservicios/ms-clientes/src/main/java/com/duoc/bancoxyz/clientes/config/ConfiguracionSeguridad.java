package com.duoc.bancoxyz.clientes.config;

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
 * <p>Guarda los datos mas sensibles del sistema, de modo que su control de
 * acceso es el mas estricto: leer exige {@code clientes.leer} y cualquier
 * escritura exige {@code clientes.escribir}, un alcance que no se concede a
 * ningun canal. Solo lo tendria un backoffice, que este entregable no incluye;
 * las altas reales entran por la migracion batch.</p>
 *
 * <p>El cajero no recibe siquiera {@code clientes.leer}: es un equipo en la via
 * publica y no tiene por que poder consultar el padron de titulares.</p>
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain cadenaDeFiltros(HttpSecurity http) throws Exception {
        http
            // Sin estado: no hay sesion que proteger, de modo que CSRF no
            // aplica. Desactivarlo con sesiones activas si seria un problema.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rutas -> rutas
                    // Abiertos porque los consulta la infraestructura sin token:
                    // Docker para decidir si el contenedor esta sano y Eureka
                    // para publicar el estado en su panel.
                    .requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/clientes/**")
                        .hasAuthority("SCOPE_clientes.leer")
                    .requestMatchers(HttpMethod.POST, "/api/clientes/**")
                        .hasAuthority("SCOPE_clientes.escribir")
                    .requestMatchers(HttpMethod.PUT, "/api/clientes/**")
                        .hasAuthority("SCOPE_clientes.escribir")
                    .requestMatchers(HttpMethod.DELETE, "/api/clientes/**")
                        .hasAuthority("SCOPE_clientes.escribir")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(servidor -> servidor.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
