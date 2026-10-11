package com.duoc.bancoxyz.authserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Autenticacion de personas: el formulario de login y los usuarios del banco.
 *
 * <p>Esta cadena de filtros es la "de la aplicacion", distinta de la del
 * protocolo OAuth 2.0. Se aplica a todo lo que no sean endpoints del protocolo:
 * el formulario de inicio de sesion, la pantalla de consentimiento y la pagina
 * que recibe el codigo de autorizacion.</p>
 */
@Configuration
public class ConfiguracionUsuarios {

    @Bean
    public SecurityFilterChain cadenaAplicacion(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(rutas -> rutas
                    // El endpoint de salud queda abierto porque Docker Compose lo
                    // consulta sin credenciales para decidir si el contenedor
                    // esta listo; si exigiera token, el healthcheck nunca pasaria
                    // y los servicios que dependen de este no arrancarian.
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/autorizado", "/css/**").permitAll()
                    .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Usuarios de demostracion.
     *
     * <p>Van en memoria porque el foco de la actividad es el flujo OAuth 2.0 y
     * no la gestion de identidades. Lo que si es equivalente a produccion es el
     * almacenamiento de la contrasena: BCrypt con sal, de modo que ni siquiera
     * leyendo la memoria del proceso se obtiene la clave original.</p>
     *
     * <p>Los roles distinguen a quien opera su propia cuenta de quien opera en
     * nombre del banco; los microservicios los leen del claim {@code roles}.</p>
     */
    @Bean
    public UserDetailsService usuarios(PasswordEncoder codificador) {
        UserDetails cliente = User.builder()
                .username("cliente")
                .password(codificador.encode("cliente123"))
                .roles("CLIENTE")
                .build();

        UserDetails ejecutivo = User.builder()
                .username("ejecutivo")
                .password(codificador.encode("ejecutivo123"))
                .roles("EJECUTIVO", "CLIENTE")
                .build();

        return new InMemoryUserDetailsManager(cliente, ejecutivo);
    }

    /**
     * Codificador delegante, no BCrypt a secas.
     *
     * <p>El mismo bean valida dos cosas distintas: las claves de los usuarios y
     * los secretos de los clientes OAuth 2.0. Estos ultimos se registran con el
     * prefijo que indica su algoritmo -{@code {noop}} para un valor en claro-,
     * una convencion que BCrypt por si solo no entiende: tomaria el prefijo
     * como parte de la clave y rechazaria al cliente con {@code invalid_client},
     * un error que no dice nada sobre su verdadera causa.</p>
     *
     * <p>El delegante lee ese prefijo y, al codificar, produce BCrypt: las
     * claves de los usuarios siguen guardandose con sal y sin reversa.</p>
     */
    @Bean
    public PasswordEncoder codificadorDeClaves() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
