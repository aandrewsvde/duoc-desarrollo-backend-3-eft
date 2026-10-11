package com.duoc.bancoxyz.notificaciones.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentacion viva de la API.
 *
 * <p>Declarar el esquema de seguridad no es cosmetico: sin el, la interfaz de
 * Swagger no ofrece donde pegar el token y toda prueba desde el navegador
 * responde 401, lo que deja la documentacion como algo que solo se lee.</p>
 */
@Configuration
public class ConfiguracionOpenApi {

    @Bean
    public OpenAPI documentacion() {
        return new OpenAPI()
                .info(new Info()
                        .title("Banco XYZ - Microservicio de notificaciones")
                        .version("1.0.0")
                        .description("""
                                Avisos generados a partir de los eventos de la saga.

                                Este servicio no tiene base de datos ni recibe llamadas de
                                los demas: se suscribe a banco.cuentas.saldo-actualizado y a
                                banco.pagos.rechazadas, y conserva en memoria los
                                ultimos avisos. Requiere el alcance pagos.leer."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
