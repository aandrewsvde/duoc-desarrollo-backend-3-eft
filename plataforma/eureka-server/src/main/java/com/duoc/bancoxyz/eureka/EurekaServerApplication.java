package com.duoc.bancoxyz.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Registro de servicios.
 *
 * <p>Resuelve un problema concreto: en un despliegue con contenedores, las
 * direcciones de los servicios no son estables. Si ms-transacciones llevara la
 * URL de ms-cuentas escrita en su configuracion, escalar ms-cuentas a tres
 * instancias obligaria a cambiarla y reiniciar. Con Eureka, ms-transacciones
 * pide "ms-cuentas" por nombre y recibe las instancias vivas en ese momento.</p>
 *
 * <p>Su panel en {@code http://localhost:8761} muestra que servicios estan
 * registrados, y es la forma mas directa de comprobar que el ecosistema se
 * levanto completo.</p>
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
