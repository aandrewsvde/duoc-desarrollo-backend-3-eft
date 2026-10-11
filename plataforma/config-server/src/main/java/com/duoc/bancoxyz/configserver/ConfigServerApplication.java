package com.duoc.bancoxyz.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Servidor de configuracion centralizada.
 *
 * <p>Es el primer servicio que debe estar arriba: los demas le piden su
 * configuracion al arrancar. Sin el, cada microservicio llevaria su propia
 * copia de los mismos parametros -la URL de Kafka, la del emisor de tokens, la
 * de Eureka- y cambiar uno obligaria a reconstruir siete imagenes.</p>
 *
 * <p>Sirve los archivos desde el <b>classpath</b> (perfil {@code native}) en
 * lugar de un repositorio Git remoto. Para esta entrega es deliberado: hace que
 * el proyecto arranque sin depender de una red externa ni de credenciales de
 * repositorio. En produccion se apuntaria a Git o a S3 y solo cambiaria esta
 * propiedad, no el codigo.</p>
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
