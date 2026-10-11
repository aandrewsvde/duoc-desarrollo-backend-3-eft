package com.duoc.bancoxyz.cuentas.config;

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
 * <p>No tiene login ni sesiones: recibe un token ya emitido y lo valida contra
 * las claves publicas del servidor de autorizacion. Esa validacion es local
 * -verificar una firma- y no requiere consultar al emisor en cada peticion, lo
 * que permite que el servicio escale sin convertir al servidor de autorizacion
 * en un cuello de botella.</p>
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain cadenaDeFiltros(HttpSecurity http) throws Exception {
        http
            // Sin estado: no hay sesion que proteger, de modo que CSRF no aplica.
            // Desactivarlo con sesiones activas si seria un problema.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rutas -> rutas
                    // Abiertos porque los consulta la infraestructura sin token:
                    // Docker para decidir si el contenedor esta sano y Eureka
                    // para publicar el estado en su panel.
                    .requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // El alcance se exige por operacion, no por servicio: un token
                    // emitido para leer no sirve para nada mas, aunque sea valido.
                    // Leer y escribir exigen alcances distintos. No es un
                    // detalle: el canal movil recibe solo cuentas.leer, de modo
                    // que aunque su token se filtrara no podria abrir ni cerrar
                    // una cuenta.
                    .requestMatchers(HttpMethod.GET, "/api/cuentas/**")
                        .hasAuthority("SCOPE_cuentas.leer")
                    .requestMatchers(HttpMethod.POST, "/api/cuentas/**")
                        .hasAuthority("SCOPE_cuentas.escribir")
                    .requestMatchers(HttpMethod.PATCH, "/api/cuentas/**")
                        .hasAuthority("SCOPE_cuentas.escribir")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(servidor -> servidor.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
