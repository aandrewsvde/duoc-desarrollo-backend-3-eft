package com.duoc.bancoxyz.clientes.config;

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
                        .title("Banco XYZ - Microservicio de clientes")
                        .version("1.0.0")
                        .description("""
                                Gestion de titulares: alta, consulta y mantenimiento.

                                Es el unico dueno de la identidad del cliente; los demas
                                servicios guardan solo un identificador que apunta aqui.
                                Mantiene el segmento comercial al dia escuchando los
                                eventos de saldo, sin preguntarselo a ms-cuentas.

                                Leer requiere el alcance clientes.leer; escribir requiere
                                clientes.escribir."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
