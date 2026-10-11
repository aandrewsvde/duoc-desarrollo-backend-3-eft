package com.duoc.bancoxyz.cuentas.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Clientes HTTP salientes.
 *
 * <p>Hay dos y no uno porque resuelven direcciones de forma distinta: el que va
 * a ms-clientes necesita que Eureka traduzca el nombre logico a una instancia,
 * y el que va al servidor de autorizacion debe ir siempre al mismo host, que es
 * el que figura como emisor en los tokens.</p>
 */
@Configuration
public class ConfiguracionClientes {

    /**
     * Cliente con balanceo de carga para llamar a otros microservicios.
     *
     * <p>Los tiempos de espera son la primera linea de defensa, anterior a
     * cualquier patron de Resilience4j: sin ellos, una dependencia que acepta la
     * conexion pero no responde dejaria el hilo esperando de forma indefinida, y
     * no habria cortocircuito que pudiera contabilizar un fallo que nunca
     * termina de ocurrir. Se dejan por encima del limite del TimeLimiter (3 s)
     * para que sea este quien corte primero y quede registrado como tal.</p>
     */
    @Bean
    @LoadBalanced
    @Primary
    public RestClient.Builder constructorBalanceado() {
        return RestClient.builder().requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(5))));
    }

    /**
     * Cliente SIN balanceo, para hablar con el servidor de autorizacion.
     *
     * <p>Tiene que ser un bean aparte y no un {@code clone()} del anterior: la
     * anotacion {@code @LoadBalanced} instala un interceptor que el clon
     * conserva, de modo que la direccion se seguiria resolviendo por Eureka y
     * la peticion fallaria con "Service Instance cannot be null". Eso ocurre
     * ademas de forma intermitente -depende de si el servidor de autorizacion
     * ya se registro-, que es la clase de fallo mas costosa de diagnosticar.</p>
     *
     * <p>Y no debe resolverse por Eureka: el token se pide siempre al mismo
     * host, el que figura como emisor y contra el que los servicios validan.</p>
     */
    @Bean
    public RestClient.Builder constructorDirecto() {
        return RestClient.builder().requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(5))));
    }
}
