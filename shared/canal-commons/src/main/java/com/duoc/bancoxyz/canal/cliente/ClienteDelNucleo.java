package com.duoc.bancoxyz.canal.cliente;

import com.duoc.bancoxyz.canal.dto.ClienteDelCore;
import com.duoc.bancoxyz.canal.dto.CuentaDelCore;
import com.duoc.bancoxyz.canal.dto.MovimientoDelCore;
import com.duoc.bancoxyz.canal.dto.PagoDelCore;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Acceso a los tres microservicios del nucleo desde un canal.
 *
 * <p>Esta en el modulo compartido porque el COMO -resolver el nombre por
 * Eureka, adjuntar el token, traducir un 404- es identico en los tres canales.
 * Lo que cambia entre ellos, y es lo que define al patron BFF, es QUE llamadas
 * hace cada uno y como arma la respuesta; eso vive en cada BFF.</p>
 *
 * <p>Las protecciones son mas livianas que las de ms-pagos a proposito. Un
 * canal esta delante del usuario: lo que necesita es fallar rapido y con un
 * mensaje claro, no insistir. Reintentar tres veces con espera creciente
 * mientras alguien mira una pantalla en blanco empeora la experiencia en vez de
 * mejorarla.</p>
 */
public class ClienteDelNucleo {

    private static final Logger log = LoggerFactory.getLogger(ClienteDelNucleo.class);

    public static final String INSTANCIA = "nucleo";

    private final RestClient clientes;
    private final RestClient cuentas;
    private final RestClient pagos;
    private final ProveedorDeTokenDeServicio tokens;

    public ClienteDelNucleo(RestClient.Builder balanceado, ProveedorDeTokenDeServicio tokens,
                            String servicioClientes, String servicioCuentas, String servicioPagos) {
        // Un RestClient por destino y no uno generico: cada uno lleva su baseUrl
        // y el balanceador resuelve el nombre contra Eureka en cada llamada, de
        // modo que escalar cualquiera de los tres funciona sin tocar nada aqui.
        this.clientes = servicioClientes == null ? null
                : balanceado.clone().baseUrl("http://" + servicioClientes).build();
        this.cuentas = balanceado.clone().baseUrl("http://" + servicioCuentas).build();
        this.pagos = balanceado.clone().baseUrl("http://" + servicioPagos).build();
        this.tokens = tokens;
    }

    private RestClient exigirClientes() {
        if (clientes == null) {
            // Le pasa al canal de cajero, que no tiene concedido clientes.leer.
            // Fallar aqui con un mensaje explicito es mejor que un NullPointer
            // tres llamadas mas abajo.
            throw new IllegalStateException(
                    "Este canal no tiene acceso al servicio de clientes");
        }
        return clientes;
    }

    @CircuitBreaker(name = INSTANCIA)
    @Retry(name = INSTANCIA)
    public ClienteDelCore cliente(int clienteId) {
        return obtener(exigirClientes(), "/api/clientes/{id}", ClienteDelCore.class,
                "cliente " + clienteId, clienteId);
    }

    @CircuitBreaker(name = INSTANCIA)
    @Retry(name = INSTANCIA)
    public CuentaDelCore cuenta(int numeroCuenta) {
        return obtener(cuentas, "/api/cuentas/{id}", CuentaDelCore.class,
                "cuenta " + numeroCuenta, numeroCuenta);
    }

    @CircuitBreaker(name = INSTANCIA)
    @Retry(name = INSTANCIA)
    public List<CuentaDelCore> cuentasDe(int clienteId) {
        Map<String, Object> cuerpo = obtener(cuentas, "/api/cuentas/de-cliente/{id}",
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "cuentas del cliente " + clienteId, clienteId);
        return convertir(cuerpo.get("cuentas"), CuentaDelCore.class);
    }

    @CircuitBreaker(name = INSTANCIA)
    @Retry(name = INSTANCIA)
    public List<MovimientoDelCore> movimientos(int numeroCuenta, int limite) {
        Map<String, Object> cuerpo = obtener(cuentas,
                "/api/cuentas/{id}/movimientos?limite=" + limite,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "movimientos de la cuenta " + numeroCuenta, numeroCuenta);
        return convertir(cuerpo.get("movimientos"), MovimientoDelCore.class);
    }

    @CircuitBreaker(name = INSTANCIA)
    @Retry(name = INSTANCIA)
    public List<PagoDelCore> pagosDe(int numeroCuenta, int limite) {
        Map<String, Object> cuerpo = obtener(pagos,
                "/api/pagos?cuentaId=" + numeroCuenta + "&limite=" + limite,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                "pagos de la cuenta " + numeroCuenta);
        return convertir(cuerpo.get("pagos"), PagoDelCore.class);
    }

    /**
     * Inicia un pago.
     *
     * <p>No lleva {@code @Retry}: reintentar una ESCRITURA tras un tiempo
     * agotado es peligroso, porque no se sabe si la primera llegó. Aqui la
     * seguridad la da la referencia idempotente que impone ms-pagos, y el canal
     * debe limitarse a informar el fallo para que sea una persona quien decida
     * repetir.</p>
     */
    @CircuitBreaker(name = INSTANCIA)
    public Map<String, Object> iniciarPago(Map<String, Object> solicitud) {
        return pagos.post()
                .uri("/api/pagos")
                .header("Authorization", "Bearer " + tokens.token())
                .body(solicitud)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (peticion, respuesta) -> {
                    throw new RecursoNoExisteException(
                            "ms-pagos rechazo la solicitud con " + respuesta.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (peticion, respuesta) -> {
                    throw new CoreNoDisponibleException(
                            "ms-pagos respondio " + respuesta.getStatusCode());
                })
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private <T> T obtener(RestClient http, String ruta, Class<T> tipo, String que, Object... args) {
        return preparar(http, ruta, que, args).body(tipo);
    }

    private <T> T obtener(RestClient http, String ruta, ParameterizedTypeReference<T> tipo,
                          String que, Object... args) {
        return preparar(http, ruta, que, args).body(tipo);
    }

    private RestClient.ResponseSpec preparar(RestClient http, String ruta, String que,
                                             Object... args) {
        log.debug("Consultando {} en el nucleo", que);
        return http.get()
                .uri(ruta, args)
                .header("Authorization", "Bearer " + tokens.token())
                .retrieve()
                // Un 404 es una respuesta correcta sobre algo que no existe: se
                // traduce a una excepcion de negocio para que NO cuente como
                // fallo del cortocircuito.
                .onStatus(HttpStatusCode::is4xxClientError, (peticion, respuesta) -> {
                    if (respuesta.getStatusCode().value() == 404) {
                        throw new RecursoNoExisteException("No existe " + que);
                    }
                    throw new CoreNoDisponibleException(
                            "El nucleo rechazo la consulta de " + que
                            + " con " + respuesta.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (peticion, respuesta) -> {
                    throw new CoreNoDisponibleException(
                            "El nucleo respondio " + respuesta.getStatusCode()
                            + " al consultar " + que);
                });
    }

    /** Convierte la lista generica que viene del JSON al tipo concreto. */
    private <T> List<T> convertir(Object valor, Class<T> tipo) {
        if (valor == null) {
            return List.of();
        }
        com.fasterxml.jackson.databind.ObjectMapper mapeador = MAPEADOR;
        return mapeador.convertValue(valor,
                mapeador.getTypeFactory().constructCollectionType(List.class, tipo));
    }

    /**
     * Mapeador con soporte de fechas.
     *
     * <p>Sin el modulo de java.time, Jackson no sabe construir un LocalDate ni
     * un Instant y falla al convertir; con el, respeta el formato ISO-8601 que
     * declara el contrato de los eventos.</p>
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPEADOR =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .build();
}
