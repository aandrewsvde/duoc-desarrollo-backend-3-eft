package com.duoc.bancoxyz.bff.web.web;

import com.duoc.bancoxyz.canal.cliente.ClienteDelNucleo;
import com.duoc.bancoxyz.canal.dto.ClienteDelCore;
import com.duoc.bancoxyz.canal.dto.CuentaDelCore;
import com.duoc.bancoxyz.canal.dto.MovimientoDelCore;
import com.duoc.bancoxyz.canal.dto.PagoDelCore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canal web: la vista COMPLETA.
 *
 * <p>Una pantalla de escritorio tiene espacio para mostrarlo todo y la conexion
 * rara vez es el cuello de botella, de modo que este canal optimiza por
 * NUMERO DE VIAJES y no por tamano: agrupa en una sola respuesta lo que de otro
 * modo serian tres o cuatro llamadas del navegador -perfil, cuentas,
 * movimientos de cada cuenta y pagos recientes-.</p>
 *
 * <p>Comparese con el canal movil, que pide exactamente los mismos datos al
 * mismo nucleo y devuelve una decima parte de bytes. Esa es la razon de ser del
 * patron: un unico backend no puede optimizar a la vez para los dos.</p>
 */
@RestController
@RequestMapping("/canal/web")
@Tag(name = "Canal web", description = "Vista completa para navegador")
public class PanelWebControlador {

    private static final Logger log = LoggerFactory.getLogger(PanelWebControlador.class);

    private final ClienteDelNucleo nucleo;
    private final int movimientosPorCuenta;
    private final String instancia;

    public PanelWebControlador(ClienteDelNucleo nucleo,
                               @Value("${banco.presentacion.movimientos-por-cuenta}") int movimientos,
                               @Value("${server.port}") String puerto,
                             @Value("${spring.cloud.client.ip-address:local}") String direccion) {
        this.nucleo = nucleo;
        this.movimientosPorCuenta = movimientos;
                // Identifica QUE REPLICA respondio, no solo el servicio.
        //
        // Con varias instancias detras del balanceador, un identificador que
        // solo lleve el puerto es identico en todas y no distingue nada: el
        // reparto podria no estar ocurriendo y la evidencia lo mostraria igual.
        // La direccion si es unica por contenedor, y es la misma con la que
        // cada instancia se registra en Eureka.
        this.instancia = "bff-web:" + direccion + ":" + puerto;
    }

    @GetMapping("/panel/{clienteId}")
    @Operation(summary = "Panel completo del titular: perfil, cuentas, movimientos y pagos")
    public Map<String, Object> panel(@PathVariable int clienteId) {
        ClienteDelCore cliente = nucleo.cliente(clienteId);
        List<CuentaDelCore> cuentas = nucleo.cuentasDe(clienteId);

        List<Map<String, Object>> detalle = cuentas.stream().map(cuenta -> {
            List<MovimientoDelCore> movimientos =
                    nucleo.movimientos(cuenta.numeroCuenta(), movimientosPorCuenta);
            List<PagoDelCore> pagos = nucleo.pagosDe(cuenta.numeroCuenta(), 5);

            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("numeroCuenta", cuenta.numeroCuenta());
            fila.put("tipoCuenta", cuenta.tipoCuenta());
            fila.put("estado", cuenta.estado());
            fila.put("saldoDisponible", cuenta.saldo());
            fila.put("moneda", cuenta.moneda());
            fila.put("totalAbonos", sumarPorSigno(movimientos, 1));
            fila.put("totalCargos", sumarPorSigno(movimientos, -1));
            fila.put("movimientosRecientes", movimientos.stream().map(m -> {
                Map<String, Object> mov = new LinkedHashMap<>();
                mov.put("fecha", m.fecha().toString());
                mov.put("tipoMovimiento", m.tipo());
                mov.put("descripcion", m.descripcion());
                mov.put("montoConSigno", m.montoConSigno());
                mov.put("origen", m.origen());
                return mov;
            }).toList());
            fila.put("pagosRecientes", pagos.stream().map(pago -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pago.id());
                p.put("tipo", pago.tipo());
                p.put("monto", pago.monto());
                p.put("estado", pago.estado());
                p.put("canalOrigen", pago.canal());
                p.put("motivoRechazo", pago.motivo());
                return p;
            }).toList());
            return fila;
        }).toList();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("canal", "WEB");
        respuesta.put("instancia", instancia);
        respuesta.put("titular", Map.of(
                "id", cliente.id(),
                "nombreCompleto", cliente.nombre(),
                "segmentoComercial", cliente.segmento(),
                "estado", cliente.estado()));
        respuesta.put("patrimonioConsolidado", cliente.saldoTotal());
        respuesta.put("totalCuentas", cuentas.size());
        respuesta.put("cuentas", detalle);

        log.info("Panel web servido para el cliente {} con {} cuentas", clienteId, cuentas.size());
        return respuesta;
    }

    @PostMapping("/pagos")
    @Operation(summary = "Inicia un pago desde el canal web")
    public ResponseEntity<Map<String, Object>> pagar(@Valid @RequestBody SolicitudPagoWeb solicitud) {
        // El canal lo pone el BFF, no el cuerpo de la peticion. Si lo declarara
        // el llamante, cualquiera podria hacer pasar una operacion de cajero por
        // una de web y esquivar los topes del terminal.
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("cuentaId", solicitud.cuentaId());
        cuerpo.put("tipo", solicitud.tipo());
        cuerpo.put("monto", solicitud.monto());
        cuerpo.put("referencia", solicitud.referencia());
        cuerpo.put("canal", "WEB");

        Map<String, Object> respuesta = nucleo.iniciarPago(cuerpo);
        return ResponseEntity.accepted().body(respuesta);
    }

    private static BigDecimal sumarPorSigno(List<MovimientoDelCore> movimientos, int signo) {
        return movimientos.stream()
                .filter(m -> m.signo() == signo)
                .map(MovimientoDelCore::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
