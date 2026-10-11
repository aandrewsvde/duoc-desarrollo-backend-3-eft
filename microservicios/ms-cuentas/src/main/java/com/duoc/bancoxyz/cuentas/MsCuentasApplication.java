package com.duoc.bancoxyz.cuentas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio de cuentas.
 *
 * <p>Es el unico propietario del saldo: ningun otro servicio escribe sobre la
 * tabla {@code cuenta}. Esa exclusividad es lo que hace que el sistema sea
 * realmente de microservicios y no un monolito repartido en varios procesos.
 * ms-pagos no "baja el saldo"; publica un hecho y este servicio decide
 * si lo acepta.</p>
 *
 * <p>Expone dos superficies distintas y deliberadamente asimetricas:</p>
 * <ul>
 *   <li>una API REST <b>de solo lectura</b>, sincrona, para quien necesita el
 *       saldo en el momento;</li>
 *   <li>un consumidor Kafka para las <b>escrituras</b>, que llegan como eventos
 *       y se resuelven de forma asincrona.</li>
 * </ul>
 */
@SpringBootApplication
public class MsCuentasApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsCuentasApplication.class, args);
    }
}
