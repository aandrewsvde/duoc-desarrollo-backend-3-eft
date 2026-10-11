package com.duoc.bancoxyz.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Puerta de entrada unica al ecosistema.
 *
 * <p>Sin ella, cada cliente tendria que conocer la direccion y el puerto de
 * cada microservicio, y cambiar uno obligaria a actualizar a todos los que lo
 * usan. Con ella, el exterior ve una sola direccion y la topologia interna
 * puede cambiar -escalar, mover, renombrar- sin que nadie se entere.</p>
 *
 * <p>Concentra ademas lo que de otro modo habria que repetir en cada servicio:
 * la validacion del token, el registro del trafico y la correlacion de
 * peticiones. Eso no sustituye la seguridad de cada microservicio, que sigue
 * validando por su cuenta: si el gateway fuera el unico control, bastaria
 * alcanzar un servicio por dentro de la red para saltarselo entero.</p>
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
