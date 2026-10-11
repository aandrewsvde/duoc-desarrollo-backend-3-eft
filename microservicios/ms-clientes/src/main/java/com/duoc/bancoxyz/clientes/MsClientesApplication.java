package com.duoc.bancoxyz.clientes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio de gestion de clientes.
 *
 * <p>Es el unico dueno de la identidad del titular. Ni ms-cuentas ni ms-pagos
 * guardan quien es una persona: guardan un identificador que apunta aqui.</p>
 *
 * <p>No llama a ningun otro servicio, y eso es deliberado. Al estar en el
 * extremo de la cadena de dependencias, su caida degrada a los demas -no se
 * pueden abrir cuentas- pero nada puede arrastrarlo a el. En un sistema
 * distribuido conviene que el componente con los datos mas sensibles sea
 * tambien el mas aislado.</p>
 *
 * <p>Si consume eventos: escucha los cambios de saldo para mantener el
 * segmento comercial al dia sin preguntarselo a nadie.</p>
 */
@SpringBootApplication
public class MsClientesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsClientesApplication.class, args);
    }
}
