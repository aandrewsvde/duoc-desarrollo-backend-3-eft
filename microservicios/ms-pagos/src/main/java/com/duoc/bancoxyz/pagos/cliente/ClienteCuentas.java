package com.duoc.bancoxyz.pagos.cliente;

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
 * Acceso sincrono a ms-cuentas, protegido por los cinco patrones de Resilience4j.
 *
 * <p>Este es el unico punto del sistema donde un microservicio llama a otro y
 * espera la respuesta, y por eso es el unico que necesita blindaje. Cada patron
 * cubre un modo de fallo distinto y por si solo ninguno basta:</p>
 *
 * <ul>
 *   <li><b>Retry</b> absorbe el fallo pasajero -un paquete perdido, un
 *       reinicio de un segundo-. Es lo primero que se intenta porque muchas
 *       averias duran menos que el tiempo que toma reaccionar a ellas.</li>
 *   <li><b>TimeLimiter</b> pone techo a la espera. Sin el, una dependencia que
 *       no responde pero tampoco cierra la conexion deja hilos colgados
 *       indefinidamente, que es peor que un error rapido.</li>
 *   <li><b>Bulkhead</b> acota cuantas llamadas simultaneas pueden estar en
 *       vuelo. Es el mamparo del casco de un barco: si ms-cuentas se degrada,
 *       la inundacion queda contenida en ese compartimento y el resto de
 *       ms-pagos -consultar el historial, por ejemplo- sigue
 *       atendiendo.</li>
 *   <li><b>RateLimiter</b> evita que este servicio sea quien tumbe al otro
 *       cuando vuelve: un servicio recien levantado que recibe de golpe toda la
 *       demanda acumulada se cae otra vez.</li>
 *   <li><b>CircuitBreaker</b> corta la llamada cuando la tasa de fallos supera
 *       el umbral. Deja de insistir contra algo que esta caido y responde al
 *       instante con el respaldo; cada cierto tiempo prueba de nuevo con unas
 *       pocas llamadas para detectar la recuperacion.</li>
 * </ul>
 *
 * <p>El orden en que se aplican no es el de las anotaciones sino el que define
 * Resilience4j: Retry envuelve a CircuitBreaker, este a RateLimiter, luego
 * TimeLimiter y por dentro Bulkhead. Asi el reintento ve la decision del
 * cortocircuito y no al reves, que es lo que se quiere: una vez abierto, el
 * cortocircuito lanza una excepcion que no figura entre las reintentables, de
 * modo que no se insiste contra algo que ya se sabe caido.</p>
 */
@Component
public class ClienteCuentas {

    private static final Logger log = LoggerFactory.getLogger(ClienteCuentas.class);

    /** Nombre de la instancia en la configuracion de Resilience4j. */
    public static final String INSTANCIA = "cuentas";

    private final RestClient http;
    private final ProveedorDeTokenDeServicio tokens;

    public ClienteCuentas(RestClient.Builder constructorBalanceado,
                          ProveedorDeTokenDeServicio tokens,
                          @Value("${banco.servicio-cuentas}") String servicioCuentas) {
        // "http://ms-cuentas" no es un nombre DNS: lo resuelve el balanceador de
        // Spring Cloud consultando a Eureka, que devuelve una instancia concreta.
        // Por eso se puede escalar ms-cuentas a varias replicas sin tocar nada aqui.
        this.http = constructorBalanceado.baseUrl("http://" + servicioCuentas).build();
        this.tokens = tokens;
    }

    /**
     * Consulta una cuenta con todas las protecciones activas.
     *
     * <p>Devuelve {@link CompletableFuture} porque el TimeLimiter necesita poder
     * cancelar la llamada desde fuera, y eso solo es posible si la ejecucion es
     * asincrona: sobre una llamada bloqueante no hay a quien interrumpir.</p>
     */
    @CircuitBreaker(name = INSTANCIA)
    // El respaldo va en @Retry y no en @CircuitBreaker, y esa colocacion es
    // decisiva. Resilience4j aplica el reintento POR FUERA del cortocircuito,
    // de modo que un respaldo declarado en el cortocircuito convertiria cada
    // fallo en un resultado correcto antes de que el reintento llegara a
    // verlo: no habria reintentos y, con una sola llamada contabilizada por
    // invocacion, el cortocircuito tardaria mucho mas en alcanzar el minimo
    // que necesita para evaluar. Declarado aqui, el respaldo es lo ultimo que
    // ocurre, una vez agotados los intentos.
    @Retry(name = INSTANCIA, fallbackMethod = "respaldo")
    @RateLimiter(name = INSTANCIA)
    @TimeLimiter(name = INSTANCIA)
    @Bulkhead(name = INSTANCIA, type = Bulkhead.Type.SEMAPHORE)
    public CompletableFuture<ValidacionPrevia> validarCuenta(int cuentaId) {
        CompletableFuture<ValidacionPrevia> enCurso =
                CompletableFuture.supplyAsync(() -> ValidacionPrevia.confirmada(consultar(cuentaId)));

        // Se devuelve un futuro propio que falla con la excepcion ORIGINAL.
        //
        // No es un rodeo gratuito. CompletableFuture envuelve en
        // CompletionException todo lo que lanza su proveedor, y Resilience4j
        // entrega esa excepcion envuelta -sin abrirla- al clasificador del
        // cortocircuito. Como la lista de excepciones que cuentan como fallo
        // nombra ServicioNoDisponibleException y no CompletionException, cada
        // caida se contabilizaria como EXITO y el cortocircuito no se abriria
        // nunca: el respaldo seguiria respondiendo, de modo que todo pareceria
        // funcionar mientras la proteccion principal esta inerte.
        CompletableFuture<ValidacionPrevia> resultado = new CompletableFuture<>();
        enCurso.whenComplete((validacion, fallo) -> {
            if (fallo == null) {
                resultado.complete(validacion);
            } else {
                resultado.completeExceptionally(desenvolver(fallo));
            }
        });
        return resultado;
    }

    /** Quita la capa que agrega CompletableFuture y deja la causa real. */
    private static Throwable desenvolver(Throwable fallo) {
        return (fallo instanceof CompletionException && fallo.getCause() != null)
                ? fallo.getCause() : fallo;
    }

    /** La llamada HTTP propiamente dicha, sin proteccion: la aporta el envoltorio. */
    private ResumenCuenta consultar(int cuentaId) {
        log.debug("Consultando la cuenta {} en ms-cuentas", cuentaId);
        return http.get()
                .uri("/api/cuentas/{id}", cuentaId)
                .header("Authorization", "Bearer " + tokens.token())
                .retrieve()
                // Un 404 es una respuesta correcta a una pregunta sobre algo que
                // no existe: se traduce a una excepcion de negocio para que NO
                // cuente como fallo del cortocircuito.
                .onStatus(HttpStatusCode::is4xxClientError, (peticion, respuesta) -> {
                    if (respuesta.getStatusCode().value() == 404) {
                        throw new RecursoNoExisteException(cuentaId);
                    }
                    throw new ServicioNoDisponibleException(
                            "ms-cuentas rechazo la consulta con " + respuesta.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (peticion, respuesta) -> {
                    throw new ServicioNoDisponibleException(
                            "ms-cuentas respondio " + respuesta.getStatusCode());
                })
                .body(ResumenCuenta.class);
    }

    /**
     * Respaldo: que hacer cuando la consulta no se pudo completar.
     *
     * <p>No inventa un saldo ni da por buena la cuenta. Devuelve "no verificada"
     * y deja que el pago siga su curso, porque quien decide de verdad es
     * ms-cuentas al aplicar el evento. Rechazar aqui seria convertir la caida de
     * una dependencia en una caida del servicio completo, que es justamente lo
     * que estos patrones existen para evitar.</p>
     *
     * <p>La cuenta inexistente se vuelve a lanzar: ese caso no es una
     * degradacion sino un dato equivocado del cliente, y debe llegarle como
     * error.</p>
     */
    @SuppressWarnings("unused")  // la invoca Resilience4j por reflexion
    private CompletableFuture<ValidacionPrevia> respaldo(int cuentaId, Throwable causa) {
        Throwable real = desenvolver(causa);

        if (real instanceof RecursoNoExisteException noExiste) {
            return CompletableFuture.failedFuture(noExiste);
        }

        String detalle = "No se pudo verificar la cuenta " + cuentaId
                + " (" + real.getClass().getSimpleName() + ": " + real.getMessage() + "). "
                + "El pago se acepta como PENDIENTE y ms-cuentas la resolvera al recibir el evento.";
        log.warn("Respaldo activado para la cuenta {}: {}", cuentaId, real.toString());
        return CompletableFuture.completedFuture(ValidacionPrevia.noVerificada(detalle));
    }
}
