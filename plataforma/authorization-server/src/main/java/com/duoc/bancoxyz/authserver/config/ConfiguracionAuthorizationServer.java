package com.duoc.bancoxyz.authserver.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;

/**
 * Configuracion del servidor de autorizacion: quien puede pedir tokens, con que
 * flujo y con que alcance.
 */
@Configuration
public class ConfiguracionAuthorizationServer {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionAuthorizationServer.class);

    /**
     * Alcances del Banco XYZ.
     *
     * <p>Son por RECURSO y por operacion, no por servicio ni por rol. Esa
     * eleccion es la que permite dar a cada canal exactamente lo que necesita:
     * el movil recibe lectura de los tres recursos y ninguna escritura, de modo
     * que un token suyo filtrado no puede mover dinero aunque sea valido.</p>
     */
    public static final String SCOPE_CLIENTES_LEER = "clientes.leer";
    public static final String SCOPE_CLIENTES_ESCRIBIR = "clientes.escribir";
    public static final String SCOPE_CUENTAS_LEER = "cuentas.leer";
    public static final String SCOPE_CUENTAS_ESCRIBIR = "cuentas.escribir";
    public static final String SCOPE_PAGOS_LEER = "pagos.leer";
    public static final String SCOPE_PAGOS_ESCRIBIR = "pagos.escribir";

    /**
     * Cadena de seguridad de los endpoints del protocolo OAuth 2.0
     * ({@code /oauth2/authorize}, {@code /oauth2/token}, {@code /oauth2/jwks}).
     *
     * <p>Va con la precedencia mas alta para que atienda esas rutas antes que
     * la cadena de la aplicacion. Si una peticion no autenticada llega al
     * endpoint de autorizacion, se la redirige al formulario de login: ese es
     * el inicio visible del flujo de codigo de autorizacion.</p>
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain cadenaProtocoloOAuth2(HttpSecurity http) throws Exception {
        // applyDefaultSecurity deja registrados los endpoints del protocolo y
        // limita esta cadena a ellos. Sobre esa base se habilita OpenID Connect,
        // que agrega /userinfo y el id_token: sin el, el flujo entregaria un
        // token de acceso pero ningun dato de identidad del usuario.
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults());

        // Si una peticion de navegador llega sin sesion al endpoint de
        // autorizacion, se la manda al formulario de login en vez de responder
        // 401. Ese redireccionamiento es el primer paso visible del flujo.
        http.exceptionHandling(excepciones -> excepciones.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

        return http.build();
    }

    /**
     * Clientes registrados.
     *
     * <p>Estan en memoria a proposito para que el proyecto arranque sin
     * administracion previa. En produccion viven en base de datos y se dan de
     * alta por un panel; el codigo que los consume no cambia.</p>
     */
    @Bean
    public RegisteredClientRepository clientesRegistrados(
            @Value("${banco.oauth.redirect-uri}") String redirectUri) {

        // -----------------------------------------------------------------
        // Cliente de PERSONA
        // -----------------------------------------------------------------
        // Aplicacion web del banco. Corre en un navegador, de modo que PKCE es
        // obligatorio: sin el, quien intercepte el codigo de autorizacion
        // -queda en el historial, en los registros del proxy- podria canjearlo
        // por un token. Con PKCE no basta el codigo, hace falta el verificador
        // que nunca salio del cliente.
        RegisteredClient usuarioWeb = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("banco-web")
                .clientSecret("{noop}banco-web-secreto-2026")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(SCOPE_CLIENTES_LEER)
                .scope(SCOPE_CUENTAS_LEER)
                .scope(SCOPE_CUENTAS_ESCRIBIR)
                .scope(SCOPE_PAGOS_LEER)
                .scope(SCOPE_PAGOS_ESCRIBIR)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .requireProofKey(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(30))
                        .refreshTokenTimeToLive(Duration.ofHours(8))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        // -----------------------------------------------------------------
        // Cliente de SERVICIO
        // -----------------------------------------------------------------
        // Con el que ms-pagos consulta a ms-cuentas y a ms-clientes. No hay
        // usuario detras: lo que se autentica es un proceso. Sus alcances son
        // estrictamente de lectura, porque un token que viaja por la red
        // interna en cada llamada es el mas expuesto de todos y no debe poder
        // mover dinero si se filtra.
        RegisteredClient servicioInterno = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("banco-servicios")
                .clientSecret("{noop}banco-servicios-secreto-2026")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(SCOPE_CLIENTES_LEER)
                .scope(SCOPE_CUENTAS_LEER)
                .scope(SCOPE_PAGOS_LEER)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .build())
                .build();

        // -----------------------------------------------------------------
        // Un cliente por CANAL
        // -----------------------------------------------------------------
        // Aqui esta la autenticacion especifica por canal que pide el caso. No
        // son tres copias del mismo cliente: cada uno tiene credenciales
        // propias y un conjunto de alcances distinto, elegido por lo que ese
        // canal puede legitimamente hacer. La consecuencia practica es que
        // comprometer un canal no abre los otros dos, y que la diferencia
        // queda escrita aqui en vez de confiarse a que cada BFF se porte bien.

        // Web: el canal completo. Es el unico de maquina que puede iniciar
        // pagos ademas del cajero, porque es donde el titular opera con sesion.
        RegisteredClient canalWeb = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("canal-web")
                .clientSecret("{noop}canal-web-secreto-2026")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(SCOPE_CLIENTES_LEER)
                .scope(SCOPE_CUENTAS_LEER)
                // Abrir y cerrar cuentas solo desde la web: es la unica
                // superficie donde el titular opera con sesion y puede leer lo
                // que firma. Ni el movil ni el cajero reciben este alcance.
                .scope(SCOPE_CUENTAS_ESCRIBIR)
                .scope(SCOPE_PAGOS_LEER)
                .scope(SCOPE_PAGOS_ESCRIBIR)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build())
                .build();

        // Movil: solo lectura. La aplicacion muestra saldos y movimientos; toda
        // operacion que mueva dinero pasa por otro canal. Un alcance que no se
        // concede no se puede abusar, y un telefono es el dispositivo mas facil
        // de perder de los tres.
        RegisteredClient canalMovil = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("canal-movil")
                .clientSecret("{noop}canal-movil-secreto-2026")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(SCOPE_CLIENTES_LEER)
                .scope(SCOPE_CUENTAS_LEER)
                .scope(SCOPE_PAGOS_LEER)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build())
                .build();

        // Cajero: puede mover dinero pero NO lee clientes. Es un equipo fisico
        // en la via publica; no se le entrega el padron de titulares. Ademas su
        // token dura cinco minutos, mucho menos que el de los otros canales,
        // porque el riesgo de que alguien acceda al terminal es mayor.
        RegisteredClient canalCajero = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("canal-cajero")
                .clientSecret("{noop}canal-cajero-secreto-2026")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(SCOPE_CUENTAS_LEER)
                .scope(SCOPE_PAGOS_LEER)
                .scope(SCOPE_PAGOS_ESCRIBIR)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();

        log.info("Clientes OAuth2 registrados: banco-web (authorization_code + PKCE); "
                + "banco-servicios, canal-web, canal-movil y canal-cajero (client_credentials). "
                + "Redirect URI: {}", redirectUri);

        return new InMemoryRegisteredClientRepository(
                usuarioWeb, servicioInterno, canalWeb, canalMovil, canalCajero);
    }

    /**
     * Agrega al token los datos que los microservicios necesitan para autorizar.
     *
     * <p>Sin esto, el token llevaria el sujeto y los scopes pero no los roles, y
     * cada servicio tendria que consultar un directorio en cada peticion para
     * saber si quien llama es cliente o ejecutivo. Incluirlos en el token
     * firmado evita ese viaje y mantiene la validacion local.</p>
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> personalizadorToken() {
        return contexto -> {
            if (contexto.getPrincipal() != null && contexto.getPrincipal().getAuthorities() != null) {
                List<String> roles = contexto.getPrincipal().getAuthorities().stream()
                        .map(Object::toString)
                        .filter(autoridad -> autoridad.startsWith("ROLE_"))
                        .toList();
                if (!roles.isEmpty()) {
                    contexto.getClaims().claim("roles", roles);
                }
            }
            contexto.getClaims().claim("banco", "XYZ");
        };
    }

    /**
     * Claves de firma.
     *
     * <p>Se genera un par RSA nuevo en cada arranque. Para esta entrega es lo
     * correcto -no hay secretos que custodiar en el repositorio- pero tiene una
     * consecuencia que conviene conocer: al reiniciar el servidor, los tokens
     * emitidos antes dejan de validar. En produccion la clave vendria de un
     * almacen persistente y rotaria de forma controlada.</p>
     */
    @Bean
    public JWKSource<SecurityContext> fuenteDeClaves() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            KeyPair par = generador.generateKeyPair();

            RSAKey clave = new RSAKey.Builder((RSAPublicKey) par.getPublic())
                    .privateKey((RSAPrivateKey) par.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();

            log.info("Par de claves RSA generado para la firma de tokens (keyId={})", clave.getKeyID());
            return new ImmutableJWKSet<>(new JWKSet(clave));
        } catch (Exception e) {
            throw new IllegalStateException("No fue posible generar las claves de firma", e);
        }
    }

    @Bean
    public JwtDecoder decodificadorJwt(JWKSource<SecurityContext> fuenteDeClaves) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(fuenteDeClaves);
    }

    /**
     * El emisor es la direccion por la que el NAVEGADOR del usuario llega aqui,
     * no el nombre interno del contenedor: es el valor que queda grabado en el
     * token y contra el que los microservicios lo contrastan.
     */
    @Bean
    public AuthorizationServerSettings ajustesServidor(@Value("${banco.oauth.issuer}") String emisor) {
        log.info("Emisor declarado en los tokens: {}", emisor);
        return AuthorizationServerSettings.builder().issuer(emisor).build();
    }
}
