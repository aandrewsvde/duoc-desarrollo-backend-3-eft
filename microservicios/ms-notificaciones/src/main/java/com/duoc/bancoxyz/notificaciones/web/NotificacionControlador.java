package com.duoc.bancoxyz.notificaciones.web;

import com.duoc.bancoxyz.notificaciones.dominio.Notificacion;
import com.duoc.bancoxyz.notificaciones.servicio.BandejaDeNotificaciones;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de los avisos generados.
 *
 * <p>Existe para que la mensajeria sea verificable: sin esta ventana, la unica
 * prueba de que los eventos llegaron serian las lineas del registro, y eso no
 * se puede consultar desde fuera del contenedor.</p>
 */
@RestController
@RequestMapping("/api/notificaciones")
@Tag(name = "Notificaciones", description = "Avisos generados a partir de los eventos de la saga")
public class NotificacionControlador {

    private final BandejaDeNotificaciones bandeja;
    private final String instancia;

    public NotificacionControlador(BandejaDeNotificaciones bandeja,
                                   @Value("${server.port}") String puerto,
                             @Value("${spring.cloud.client.ip-address:local}") String direccion) {
        this.bandeja = bandeja;
                // Identifica QUE REPLICA respondio, no solo el servicio.
        //
        // Con varias instancias detras del balanceador, un identificador que
        // solo lleve el puerto es identico en todas y no distingue nada: el
        // reparto podria no estar ocurriendo y la evidencia lo mostraria igual.
        // La direccion si es unica por contenedor, y es la misma con la que
        // cada instancia se registra en Eureka.
        this.instancia = "ms-notificaciones:" + direccion + ":" + puerto;
    }

    @GetMapping
    @Operation(summary = "Ultimas notificaciones, opcionalmente de una cuenta")
    public Map<String, Object> listar(@RequestParam(required = false) Integer cuentaId,
                                      @RequestParam(defaultValue = "20") int limite) {
        List<Notificacion> notificaciones = (cuentaId == null)
                ? bandeja.ultimas(limite)
                : bandeja.deCuenta(cuentaId, limite);

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("instancia", instancia);
        cuerpo.put("recibidasDesdeElArranque", bandeja.totalRecibidas());
        cuerpo.put("enMemoria", bandeja.enMemoria());
        cuerpo.put("total", notificaciones.size());
        cuerpo.put("notificaciones", notificaciones);
        return cuerpo;
    }
}
