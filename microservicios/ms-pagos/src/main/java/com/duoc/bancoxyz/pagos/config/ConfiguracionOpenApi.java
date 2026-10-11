package com.duoc.bancoxyz.pagos.config;

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
                        .title("Banco XYZ - Microservicio de pagos")
                        .version("1.0.0")
                        .description("""
                                Origen de la saga de pagos del Banco XYZ.

                                Crear un pago la deja en estado PENDIENTE y publica
                                el evento banco.pagos.creadas. El estado definitivo
                                -CONFIRMADA o COMPENSADA- llega despues, cuando ms-cuentas
                                informa el desenlace por su propio topico.

                                Leer requiere el alcance pagos.leer; crear requiere
                                pagos.escribir."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
