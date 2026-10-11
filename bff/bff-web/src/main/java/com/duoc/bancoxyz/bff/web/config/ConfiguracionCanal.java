package com.duoc.bancoxyz.bff.web.config;

import com.duoc.bancoxyz.canal.cliente.ClienteDelNucleo;
import com.duoc.bancoxyz.canal.cliente.ProveedorDeTokenDeServicio;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Clientes HTTP salientes de este canal.
 *
 * <p>Hay dos constructores y no uno porque resuelven direcciones de forma
 * distinta: el que va a los microservicios necesita que Eureka traduzca el
 * nombre logico a una instancia, y el que va al servidor de autorizacion debe
 * ir siempre al mismo host, que es el que figura como emisor en los tokens.</p>
 */
@Configuration
public class ConfiguracionCanal {

    /**
     * Cliente con balanceo de carga hacia los microservicios.
     *
     * <p>Los tiempos de espera son la primera linea de defensa, anterior a
     * cualquier patron de Resilience4j: sin ellos, un microservicio que acepta
     * la conexion pero no responde dejaria el hilo esperando de forma
     * indefinida, y no habria cortocircuito que pudiera contabilizar un fallo
     * que nunca termina de ocurrir.</p>
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
     * <p>Tiene que ser un bean aparte y no un clon del anterior: la anotacion
     * {@code @LoadBalanced} instala un interceptor que el clon conserva, de
     * modo que la direccion se seguiria resolviendo por Eureka y la peticion
     * fallaria con "Service Instance cannot be null", de forma intermitente
     * segun si el servidor ya se registro.</p>
     */
    @Bean
    public RestClient.Builder constructorDirecto() {
        return RestClient.builder().requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(5))));
    }

    @Bean
    public ProveedorDeTokenDeServicio proveedorDeToken(
            @Qualifier("constructorDirecto") RestClient.Builder constructor,
            @Value("${banco.oauth.token-uri}") String tokenUri,
            @Value("${banco.oauth.client-id}") String clientId,
            @Value("${banco.oauth.client-secret}") String clientSecret,
            @Value("${banco.oauth.scopes}") String alcances) {
        return new ProveedorDeTokenDeServicio(constructor, tokenUri, clientId,
                clientSecret, alcances);
    }

    @Bean
    public ClienteDelNucleo clienteDelNucleo(
            @Qualifier("constructorBalanceado") RestClient.Builder balanceado,
            ProveedorDeTokenDeServicio tokens,
            @Value("${banco.servicio-clientes}") String servicioClientes,
            @Value("${banco.servicio-cuentas}") String servicioCuentas,
            @Value("${banco.servicio-pagos}") String servicioPagos) {
        return new ClienteDelNucleo(balanceado, tokens, servicioClientes,
                servicioCuentas, servicioPagos);
    }
}
