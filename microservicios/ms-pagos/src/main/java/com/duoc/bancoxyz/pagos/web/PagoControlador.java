package com.duoc.bancoxyz.pagos.web;

import com.duoc.bancoxyz.pagos.dominio.SolicitudPago;
import com.duoc.bancoxyz.pagos.dominio.Pago;
import com.duoc.bancoxyz.pagos.servicio.PagoServicio;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de pagos. */
@RestController
@RequestMapping("/api/pagos")
@Tag(name = "Pagos", description = "Alta y consulta de pagos bancarias")
public class PagoControlador {

    private final PagoServicio servicio;
    private final String instancia;

    public PagoControlador(PagoServicio servicio,
                                  @Value("${server.port}") String puerto,
                             @Value("${spring.cloud.client.ip-address:local}") String direccion) {
        this.servicio = servicio;
                // Identifica QUE REPLICA respondio, no solo el servicio.
        //
        // Con varias instancias detras del balanceador, un identificador que
        // solo lleve el puerto es identico en todas y no distingue nada: el
        // reparto podria no estar ocurriendo y la evidencia lo mostraria igual.
        // La direccion si es unica por contenedor, y es la misma con la que
        // cada instancia se registra en Eureka.
        this.instancia = "ms-pagos:" + direccion + ":" + puerto;
    }

    /**
     * Crea un pago.
     *
     * <p>Responde 202 y no 201: la operacion fue aceptada, pero todavia no se
     * completo. Devolver 201 sugeriria que el dinero ya se movio, cuando lo que
     * existe es un pago PENDIENTE cuyo desenlace llegara por evento. El
     * codigo de estado es parte del contrato y debe decir la verdad.</p>
     *
     * <p>Si la referencia ya se habia usado responde 200 con el pago
     * original: un reintento no crea nada nuevo.</p>
     */
    @PostMapping
    @Operation(summary = "Registra un pago e inicia la saga")
    public ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody SolicitudPago solicitud) {
        PagoServicio.ResultadoCreacion resultado = servicio.crear(solicitud);

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("instancia", instancia);
        cuerpo.put("pago", resultado.pago());
        cuerpo.put("observacion", resultado.observacion());
        cuerpo.put("siguientePaso", resultado.yaExistia()
                ? "Sin accion: la referencia ya estaba registrada"
                : "ms-cuentas aplicara el evento y el estado pasara a CONFIRMADA o COMPENSADA");

        HttpStatus estado = resultado.yaExistia() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(estado).body(cuerpo);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta un pago y su estado dentro de la saga")
    public Pago obtener(@PathVariable String id) {
        return servicio.obtener(id);
    }

    @GetMapping
    @Operation(summary = "Lista pagos, opcionalmente filtradas por cuenta")
    public Map<String, Object> listar(@RequestParam(required = false) Integer cuentaId,
                                      @RequestParam(defaultValue = "20") int limite) {
        List<Pago> pagos = (cuentaId == null)
                ? servicio.listar(limite)
                : servicio.listarDeCuenta(cuentaId, limite);

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("instancia", instancia);
        cuerpo.put("total", pagos.size());
        cuerpo.put("pagos", pagos);
        return cuerpo;
    }
}
