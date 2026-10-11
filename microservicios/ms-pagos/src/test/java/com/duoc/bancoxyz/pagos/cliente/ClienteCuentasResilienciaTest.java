package com.duoc.bancoxyz.pagos.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Verifica que los patrones de resiliencia hacen lo que se espera de ellos.
 *
 * <p>No se simula {@link ClienteCuentas}: se levanta un servidor HTTP de
 * verdad y se le indica como portarse. La razon es que lo que se quiere
 * comprobar -que un tiempo de espera agotado dispare el respaldo, que la tasa
 * de fallos abra el cortocircuito- ocurre en el transporte, y un doble de
 * prueba que lance excepciones a voluntad verificaria la configuracion de
 * Resilience4j sin pasar nunca por la red que esa configuracion protege.</p>
 */
@SpringBootTest
class ClienteCuentasResilienciaTest {

    /** Como debe comportarse el servidor falso en la prueba en curso. */
    private enum Comportamiento { RESPONDE_BIEN, ERROR_DEL_SERVIDOR, NO_ENCONTRADA, DEMORA }

    private static final AtomicReference<Comportamiento> COMPORTAMIENTO =
            new AtomicReference<>(Comportamiento.RESPONDE_BIEN);

    private static HttpServer servidor;
    private static int puerto;

    @BeforeAll
    static void levantarServidorFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        puerto = servidor.getAddress().getPort();
        servidor.createContext("/api/cuentas", intercambio -> {
            switch (COMPORTAMIENTO.get()) {
                case RESPONDE_BIEN -> responder(intercambio, 200, """
                        {"numeroCuenta":101,"titular":"Diana Prince","saldo":8000.00,
                         "moneda":"CLP","estado":"ACTIVA"}""");
                case ERROR_DEL_SERVIDOR -> responder(intercambio, 500, "{\"error\":\"caido\"}");
                case NO_ENCONTRADA -> responder(intercambio, 404, "{\"error\":\"no existe\"}");
                case DEMORA -> {
                    try {
                        // Por encima del limite del TimeLimiter (1 s en pruebas).
                        Thread.sleep(3000);
                    } catch (InterruptedException interrumpido) {
                        Thread.currentThread().interrupt();
                    }
                    responder(intercambio, 200, "{\"numeroCuenta\":101}");
                }
            }
        });
        servidor.start();
    }

    private static void responder(com.sun.net.httpserver.HttpExchange intercambio,
                                  int codigo, String cuerpo) throws IOException {
        byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/json");
        intercambio.sendResponseHeaders(codigo, datos.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(datos);
        }
    }

    @AfterAll
    static void apagarServidorFalso() {
        servidor.stop(0);
    }

    @DynamicPropertySource
    static void apuntarAlServidorFalso(DynamicPropertyRegistry propiedades) {
        // El puerto solo se conoce en tiempo de ejecucion, de modo que no puede
        // ir en el application.yml de pruebas.
        propiedades.add("banco.servicio-cuentas", () -> "127.0.0.1:" + puerto);
    }

    /**
     * Sustituye el cliente con balanceo por uno directo.
     *
     * <p>El balanceador resolveria "ms-cuentas" consultando a Eureka, que en
     * las pruebas no existe. Aqui interesa el comportamiento ante fallos, no el
     * descubrimiento de instancias.</p>
     */
    @TestConfiguration
    static class ClienteDirecto {
        @Bean
        @Primary
        RestClient.Builder constructorBalanceado() {
            return RestClient.builder();
        }
    }

    /** El token no se obtiene de verdad: el servidor de autorizacion no corre. */
    @MockBean
    private ProveedorDeTokenDeServicio tokens;

    @Autowired
    private ClienteCuentas cliente;

    @Autowired
    private CircuitBreakerRegistry registro;

    @BeforeEach
    void dejarElCortocircuitoLimpio() {
        registro.circuitBreaker(ClienteCuentas.INSTANCIA).reset();
        COMPORTAMIENTO.set(Comportamiento.RESPONDE_BIEN);
    }

    @Test
    @DisplayName("Con ms-cuentas sano, la cuenta queda verificada")
    void consultaCorrecta() {
        ValidacionPrevia validacion = cliente.validarCuenta(101).join();

        assertThat(validacion.verificada()).isTrue();
        assertThat(validacion.cuenta()).isNotNull();
        assertThat(validacion.cuenta().saldo()).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("Una cuenta inexistente se propaga como error y no como degradacion")
    void cuentaInexistenteNoActivaElRespaldo() {
        COMPORTAMIENTO.set(Comportamiento.NO_ENCONTRADA);

        assertThatThrownBy(() -> cliente.validarCuenta(999).join())
                .hasRootCauseInstanceOf(RecursoNoExisteException.class);
    }

    /**
     * El 404 no debe contar como fallo del servicio. Si contara, bastaria con
     * pedir cuentas inexistentes para abrir el cortocircuito y dejar sin
     * servicio una dependencia que esta perfectamente sana.
     */
    @Test
    @DisplayName("Los 404 no cuentan como fallo del cortocircuito")
    void cuentaInexistenteNoCuentaComoFallo() {
        COMPORTAMIENTO.set(Comportamiento.NO_ENCONTRADA);
        CircuitBreaker cortocircuito = registro.circuitBreaker(ClienteCuentas.INSTANCIA);

        for (int intento = 0; intento < 8; intento++) {
            try {
                cliente.validarCuenta(999).join();
            } catch (RuntimeException esperada) {
                // se ignora: lo que se mide es el estado del cortocircuito
            }
        }

        assertThat(cortocircuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cortocircuito.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Si ms-cuentas responde 500, el respaldo deja seguir sin verificar")
    void errorDelServidorActivaElRespaldo() {
        COMPORTAMIENTO.set(Comportamiento.ERROR_DEL_SERVIDOR);

        ValidacionPrevia validacion = cliente.validarCuenta(101).join();

        assertThat(validacion.verificada()).isFalse();
        assertThat(validacion.cuenta()).isNull();
        assertThat(validacion.detalle()).contains("No se pudo verificar la cuenta 101");
    }

    @Test
    @DisplayName("Una respuesta mas lenta que el limite de tiempo activa el respaldo")
    void laDemoraActivaElRespaldo() {
        COMPORTAMIENTO.set(Comportamiento.DEMORA);

        long inicio = System.currentTimeMillis();
        ValidacionPrevia validacion = cliente.validarCuenta(101).join();
        long transcurrido = System.currentTimeMillis() - inicio;

        assertThat(validacion.verificada()).isFalse();
        // Lo importante no es solo que degrade, sino que lo haga PRONTO: el
        // servidor tarda 3 s y aqui se corta mucho antes, aun contando los
        // reintentos. Sin limite de tiempo, la peticion esperaria los 3 s.
        assertThat(transcurrido).isLessThan(3000L);
    }

    /**
     * La prueba central del cortocircuito: tras suficientes fallos deja de
     * llamar y responde al instante con el respaldo, que es lo que impide que
     * una dependencia caida arrastre a quien la consume.
     */
    @Test
    @DisplayName("Los fallos sostenidos abren el cortocircuito")
    void losFallosSostenidosAbrenElCortocircuito() {
        COMPORTAMIENTO.set(Comportamiento.ERROR_DEL_SERVIDOR);
        CircuitBreaker cortocircuito = registro.circuitBreaker(ClienteCuentas.INSTANCIA);

        assertThat(cortocircuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Cada invocacion reintenta 3 veces y cada intento se contabiliza, de
        // modo que dos invocaciones bastan para superar el minimo de 5 llamadas
        // que la ventana exige antes de evaluar la tasa de fallos.
        for (int intento = 0; intento < 2; intento++) {
            cliente.validarCuenta(101).join();
        }

        assertThat(cortocircuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Con el cortocircuito abierto la llamada ya no viaja: responde el
        // respaldo de inmediato.
        long inicio = System.currentTimeMillis();
        ValidacionPrevia validacion = cliente.validarCuenta(101).join();
        long transcurrido = System.currentTimeMillis() - inicio;

        assertThat(validacion.verificada()).isFalse();
        assertThat(transcurrido).isLessThan(200L);
    }
}
