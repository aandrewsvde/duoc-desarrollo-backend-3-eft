package com.duoc.bancoxyz.notificaciones;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio de notificaciones.
 *
 * <p>Es la prueba concreta de para que sirve la mensajeria. Nadie lo llama:
 * se suscribe a los topicos que ya existian y empieza a avisar. Ni ms-cuentas
 * ni ms-pagos tuvieron que cambiar una linea ni enterarse de que este
 * servicio existe, y si se apaga, los otros dos siguen funcionando igual.</p>
 *
 * <p>Con llamadas sincronas esto no seria asi: habria que modificar al emisor
 * para que invocara tambien a este servicio, y su caida pasaria a afectar a
 * quien lo llama.</p>
 */
@SpringBootApplication
public class MsNotificacionesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsNotificacionesApplication.class, args);
    }
}
