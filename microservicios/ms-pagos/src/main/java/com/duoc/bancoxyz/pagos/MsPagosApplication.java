package com.duoc.bancoxyz.pagos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio de pagos.
 *
 * <p>Es el origen de la saga y el unico servicio del sistema que depende de
 * otro de forma sincrona, de modo que es tambien el unico que necesita
 * protegerse con los patrones de resiliencia.</p>
 *
 * <p>Un pago recorre tres estados:</p>
 * <ol>
 *   <li><b>PENDIENTE</b>: se registro aqui y se publico el evento. El saldo
 *       todavia no cambio.</li>
 *   <li><b>CONFIRMADA</b>: ms-cuentas aplico el cambio y lo informo.</li>
 *   <li><b>COMPENSADA</b>: ms-cuentas lo rechazo y el pago se revierte
 *       con su motivo.</li>
 * </ol>
 *
 * <p>No hay pago distribuida ni bloqueo de dos fases entre servicios:
 * cada uno confirma localmente y el acuerdo se alcanza por los eventos. El
 * precio es que existe una ventana en la que el sistema esta en un estado
 * intermedio, y por eso el estado PENDIENTE es visible en la API en lugar de
 * esconderse.</p>
 */
@SpringBootApplication
public class MsPagosApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsPagosApplication.class, args);
    }
}
