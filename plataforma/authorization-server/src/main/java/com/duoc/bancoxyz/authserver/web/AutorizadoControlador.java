package com.duoc.bancoxyz.authserver.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Pagina de retorno del flujo de codigo de autorizacion.
 *
 * <p>En una aplicacion real aqui estaria el frontend del banco, que canjearia
 * el codigo por un token sin mostrarselo a nadie. Para esta entrega la pagina
 * muestra el codigo recibido y el comando exacto para canjearlo, de modo que el
 * paso intermedio del flujo -normalmente invisible- quede documentado en la
 * evidencia y se pueda reproducir desde la terminal.</p>
 */
@Controller
public class AutorizadoControlador {

    private static final Logger log = LoggerFactory.getLogger(AutorizadoControlador.class);

    private final String emisor;
    private final String redirectUri;

    public AutorizadoControlador(@Value("${banco.oauth.issuer}") String emisor,
                                 @Value("${banco.oauth.redirect-uri}") String redirectUri) {
        this.emisor = emisor;
        // El redirect NO se deriva del emisor. Son direcciones distintas a
        // proposito -localhost para el emisor, 127.0.0.1 para el retorno- y el
        // canje del codigo exige que el redirect_uri coincida EXACTAMENTE con
        // el registrado. Componerlo a mano a partir del emisor produce un
        // comando que falla con invalid_grant sin explicar por que.
        this.redirectUri = redirectUri;
    }

    @GetMapping("/autorizado")
    public String autorizado(@RequestParam(value = "code", required = false) String codigo,
                             @RequestParam(value = "error", required = false) String error,
                             @RequestParam(value = "state", required = false) String estado,
                             Model modelo) {

        if (error != null) {
            log.warn("El flujo de autorizacion termino con error: {}", error);
        } else if (codigo != null) {
            log.info("Codigo de autorizacion recibido (state={}), vigente por unos segundos y de un solo uso", estado);
        }

        modelo.addAttribute("codigo", codigo);
        modelo.addAttribute("error", error);
        modelo.addAttribute("estado", estado);
        modelo.addAttribute("emisor", emisor);
        modelo.addAttribute("redirectUri", redirectUri);
        return "autorizado";
    }
}
