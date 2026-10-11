package com.duoc.bancoxyz.cuentas.cliente;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Obtiene el token con el que ms-pagos se identifica ante ms-clientes.
 *
 * <p>Usa el flujo {@code client_credentials}: aqui no hay usuario, hay un
 * proceso. Reutilizar el token del usuario que origino la peticion seria
 * tentador pero esta mal, porque el servicio tambien actua sin peticion detras
 * -al reaccionar a un evento- y porque entonces sus permisos dependerian de
 * quien lo invoco en lugar de lo que el servicio tiene autorizado hacer.</p>
 *
 * <p>El token se guarda en memoria hasta poco antes de caducar. Sin ese cache,
 * cada consulta a ms-clientes costaria dos viajes de red en lugar de uno y el
 * servidor de autorizacion recibiria tanto trafico como el propio negocio.</p>
 */
@Component
public class ProveedorDeTokenDeServicio {

    private static final Logger log = LoggerFactory.getLogger(ProveedorDeTokenDeServicio.class);

    /** Margen con que se renueva antes de caducar, para no usar uno recien vencido. */
    private static final Duration MARGEN_RENOVACION = Duration.ofSeconds(30);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String alcances;
    private final AtomicReference<TokenVigente> enCache = new AtomicReference<>();

    public ProveedorDeTokenDeServicio(@Qualifier("constructorDirecto") RestClient.Builder constructor,
                                      @Value("${banco.oauth.token-uri}") String tokenUri,
                                      @Value("${banco.oauth.client-id}") String clientId,
                                      @Value("${banco.oauth.client-secret}") String clientSecret,
                                      @Value("${banco.oauth.scopes}") String alcances) {
        // Constructor SIN balanceo, inyectado por nombre: el servidor de
        // autorizacion se alcanza por su direccion directa y no a traves de
        // Eureka, porque el emisor del token debe ser siempre el mismo host que
        // figura en el claim "iss" que los demas servicios verifican.
        this.http = constructor.baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.alcances = alcances;
    }

    public String token() {
        TokenVigente actual = enCache.get();
        if (actual != null && actual.sigueVigente()) {
            return actual.valor();
        }
        return renovar();
    }

    private synchronized String renovar() {
        TokenVigente actual = enCache.get();
        if (actual != null && actual.sigueVigente()) {
            return actual.valor();   // otro hilo lo renovo mientras esperabamos
        }

        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        formulario.add("grant_type", "client_credentials");
        formulario.add("scope", alcances);

        RespuestaToken respuesta = http.post()
                .headers(cabeceras -> cabeceras.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formulario)
                .retrieve()
                .body(RespuestaToken.class);

        if (respuesta == null || respuesta.accessToken() == null) {
            throw new ClientesNoDisponibleException(
                    "El servidor de autorizacion no entrego un token de servicio");
        }

        Instant caduca = Instant.now().plusSeconds(respuesta.expiraEnSegundos());
        enCache.set(new TokenVigente(respuesta.accessToken(), caduca));
        log.info("Token de servicio obtenido por client_credentials para {} (alcances: {}), vigente hasta {}",
                clientId, alcances, caduca);
        return respuesta.accessToken();
    }

    private record TokenVigente(String valor, Instant caduca) {
        boolean sigueVigente() {
            return Instant.now().isBefore(caduca.minus(MARGEN_RENOVACION));
        }
    }

    private record RespuestaToken(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiraEnSegundos,
            @JsonProperty("token_type") String tipo,
            @JsonProperty("scope") String alcance) {
    }
}
