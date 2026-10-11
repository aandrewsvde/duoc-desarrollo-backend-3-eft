package com.duoc.bancoxyz.cuentas.cliente;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Acceso sincrono a ms-clientes, protegido por Resilience4j.
 *
 * <p>Se usa en una sola operacion: verificar al titular antes de abrir una
 * cuenta. Es una llamada poco frecuente y de alto impacto, justo lo contrario
 * del trafico que protege ms-pagos, y por eso su respaldo decide al reves.</p>
 *
 * <p><b>El respaldo aqui falla cerrado.</b> Si no se puede verificar al
 * cliente, la apertura se rechaza con 503 y el canal reintenta mas tarde.
 * Abrir la cuenta igualmente crearia un registro a nombre de alguien que quiza
 * no existe, y deshacerlo despues es trabajo manual sobre datos financieros.
 * En ms-pagos la decision correcta es la contraria, porque alli el evento se
 * resuelve solo cuando el servicio vuelve.</p>
 */
@Component
public class ClienteDeClientes {

    private static final Logger log = LoggerFactory.getLogger(ClienteDeClientes.class);

    /** Nombre de la instancia en la configuracion de Resilience4j. */
    public static final String INSTANCIA = "clientes";

    private final RestClient http;
    private final ProveedorDeTokenDeServicio tokens;

    public ClienteDeClientes(RestClient.Builder constructorBalanceado,
                             ProveedorDeTokenDeServicio tokens,
                             @Value("${banco.servicio-clientes}") String servicioClientes) {
        // "http://ms-clientes" no es un nombre DNS: lo resuelve el balanceador
        // de Spring Cloud consultando a Eureka, que devuelve una instancia
        // concreta. Por eso se puede escalar ms-clientes sin tocar nada aqui.
        this.http = constructorBalanceado.baseUrl("http://" + servicioClientes).build();
        this.tokens = tokens;
    }

    @CircuitBreaker(name = INSTANCIA)
    // El respaldo va en @Retry y no en @CircuitBreaker: Resilience4j aplica el
    // reintento POR FUERA del cortocircuito, de modo que un respaldo declarado
    // en el cortocircuito convertiria cada fallo en un resultado correcto antes
    // de que el reintento llegara a verlo, y no habria reintentos en absoluto.
    @Retry(name = INSTANCIA, fallbackMethod = "respaldo")
    @RateLimiter(name = INSTANCIA)
    @TimeLimiter(name = INSTANCIA)
    @Bulkhead(name = INSTANCIA, type = Bulkhead.Type.SEMAPHORE)
    public CompletableFuture<ResumenCliente> verificar(int clienteId) {
        CompletableFuture<ResumenCliente> enCurso =
                CompletableFuture.supplyAsync(() -> consultar(clienteId));

        // Se devuelve un futuro propio que falla con la excepcion ORIGINAL.
        //
        // CompletableFuture envuelve en CompletionException todo lo que lanza
        // su proveedor, y Resilience4j entrega esa excepcion envuelta -sin
        // abrirla- al clasificador del cortocircuito. Como la lista de
        // excepciones que cuentan como fallo nombra ClientesNoDisponibleException
        // y no CompletionException, cada caida se contabilizaria como EXITO y el
        // cortocircuito no se abriria nunca.
        CompletableFuture<ResumenCliente> resultado = new CompletableFuture<>();
        enCurso.whenComplete((cliente, fallo) -> {
            if (fallo == null) {
                resultado.complete(cliente);
            } else {
                resultado.completeExceptionally(desenvolver(fallo));
            }
        });
        return resultado;
    }

    private static Throwable desenvolver(Throwable fallo) {
        return (fallo instanceof CompletionException && fallo.getCause() != null)
                ? fallo.getCause() : fallo;
    }

    private ResumenCliente consultar(int clienteId) {
        log.debug("Verificando el cliente {} en ms-clientes", clienteId);
        return http.get()
                .uri("/api/clientes/{id}", clienteId)
                .header("Authorization", "Bearer " + tokens.token())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (peticion, respuesta) -> {
                    if (respuesta.getStatusCode().value() == 404) {
                        throw new ClienteNoExisteException(clienteId);
                    }
                    throw new ClientesNoDisponibleException(
                            "ms-clientes rechazo la consulta con " + respuesta.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (peticion, respuesta) -> {
                    throw new ClientesNoDisponibleException(
                            "ms-clientes respondio " + respuesta.getStatusCode());
                })
                .body(ResumenCliente.class);
    }

    /**
     * Respaldo: no inventa un cliente, rechaza la apertura.
     *
     * <p>Un cliente inexistente se vuelve a lanzar tal cual, porque ese caso no
     * es una degradacion sino un dato equivocado de quien llama.</p>
     */
    @SuppressWarnings("unused")  // la invoca Resilience4j por reflexion
    private CompletableFuture<ResumenCliente> respaldo(int clienteId, Throwable causa) {
        Throwable real = desenvolver(causa);

        if (real instanceof ClienteNoExisteException noExiste) {
            return CompletableFuture.failedFuture(noExiste);
        }

        log.warn("No se pudo verificar el cliente {}; la apertura se rechaza. Causa: {}",
                clienteId, real.toString());
        return CompletableFuture.failedFuture(new ClientesNoDisponibleException(
                "No se pudo verificar el cliente " + clienteId + " ("
                + real.getClass().getSimpleName() + "). La apertura no se realizo; "
                + "reintente cuando ms-clientes vuelva a responder.", real));
    }
}
