package com.duoc.bancoxyz.bff.cajero.web;

import com.duoc.bancoxyz.canal.cliente.ClienteDelNucleo;
import com.duoc.bancoxyz.canal.dto.CuentaDelCore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canal de cajeros: el mas restringido de los tres, y a proposito.
 *
 * <p>Un cajero es un equipo fisico en la via publica. Si alguien se lleva el
 * terminal, se lleva sus credenciales: por eso este canal no tiene concedido
 * {@code clientes.leer}, no devuelve el nombre del titular, no muestra
 * historial y su token dura cinco minutos en lugar de quince.</p>
 *
 * <p>Ademas impone reglas que no existen en el nucleo y que son propias del
 * canal: un tope por operacion y multiplos de billete. No viven en ms-pagos
 * porque el mismo titular puede retirar mas desde una sucursal; son
 * limitaciones del terminal, no del dinero.</p>
 */
@RestController
@RequestMapping("/canal/cajero")
@Tag(name = "Canal cajero", description = "Operaciones de terminal de autoservicio")
public class CajeroControlador {

    private static final Logger log = LoggerFactory.getLogger(CajeroControlador.class);

    private final ClienteDelNucleo nucleo;
    private final BigDecimal montoMaximo;
    private final BigDecimal multiploBillete;
    private final String instancia;

    public CajeroControlador(ClienteDelNucleo nucleo,
                             @Value("${banco.cajero.monto-maximo-retiro}") BigDecimal montoMaximo,
                             @Value("${banco.cajero.multiplo-billete}") BigDecimal multiplo,
                             @Value("${server.port}") String puerto,
                             @Value("${spring.cloud.client.ip-address:local}") String direccion) {
        this.nucleo = nucleo;
        this.montoMaximo = montoMaximo;
        this.multiploBillete = multiplo;
                // Identifica QUE REPLICA respondio, no solo el servicio.
        //
        // Con varias instancias detras del balanceador, un identificador que
        // solo lleve el puerto es identico en todas y no distingue nada: el
        // reparto podria no estar ocurriendo y la evidencia lo mostraria igual.
        // La direccion si es unica por contenedor, y es la misma con la que
        // cada instancia se registra en Eureka.
        this.instancia = "bff-cajero:" + direccion + ":" + puerto;
    }

    /**
     * Consulta de saldo.
     *
     * <p>Devuelve cuatro campos y ninguno identifica al titular. Es todo lo que
     * un cajero necesita mostrar en pantalla, y cuanto menos viaje hasta el
     * terminal, menos hay que proteger alli.</p>
     */
    @GetMapping("/saldo/{cuentaId}")
    @Operation(summary = "Saldo disponible de la cuenta, sin datos del titular")
    public Map<String, Object> saldo(@PathVariable int cuentaId) {
        CuentaDelCore cuenta = nucleo.cuenta(cuentaId);

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("canal", "CAJERO");
        respuesta.put("instancia", instancia);
        respuesta.put("cuenta", cuenta.numeroCuenta());
        respuesta.put("saldoDisponible", cuenta.saldo().setScale(0, java.math.RoundingMode.DOWN));
        respuesta.put("operativa", "ACTIVA".equals(cuenta.estado()));
        respuesta.put("maximoPorOperacion", montoMaximo);
        return respuesta;
    }

    @PostMapping("/retiro")
    @Operation(summary = "Retiro por terminal, con tope y multiplo de billete")
    public ResponseEntity<Map<String, Object>> retiro(@Valid @RequestBody SolicitudRetiro solicitud) {
        // Las reglas del canal se comprueban ANTES de llamar al nucleo. Un
        // monto que el cajero no puede entregar fisicamente no tiene por que
        // recorrer tres servicios para ser rechazado.
        String rechazo = validarReglasDelTerminal(solicitud.monto());
        if (rechazo != null) {
            log.warn("Retiro rechazado por el terminal {}: {}", solicitud.terminal(), rechazo);
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("canal", "CAJERO");
            cuerpo.put("aceptado", false);
            cuerpo.put("motivo", rechazo);
            return ResponseEntity.unprocessableEntity().body(cuerpo);
        }

        Map<String, Object> peticion = new LinkedHashMap<>();
        peticion.put("cuentaId", solicitud.cuentaId());
        peticion.put("tipo", "RETIRO");
        peticion.put("monto", solicitud.monto());
        // El terminal queda en la referencia: es lo que permite rastrear una
        // operacion reclamada hasta el equipo concreto que la origino.
        peticion.put("referencia", solicitud.terminal() + "-" + solicitud.referencia());
        peticion.put("canal", "CAJERO");

        Map<String, Object> respuesta = nucleo.iniciarPago(peticion);

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("canal", "CAJERO");
        cuerpo.put("instancia", instancia);
        cuerpo.put("aceptado", true);
        cuerpo.put("terminal", solicitud.terminal());
        cuerpo.put("resultado", respuesta);
        // 202 y no 200: el cajero debe esperar la confirmacion antes de
        // entregar los billetes. Un 200 le diria que el dinero ya salio de la
        // cuenta, y todavia no.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(cuerpo);
    }

    /** @return el motivo del rechazo, o {@code null} si la orden es admisible */
    private String validarReglasDelTerminal(BigDecimal monto) {
        if (monto.compareTo(montoMaximo) > 0) {
            return "El monto supera el maximo por operacion de este canal ("
                   + montoMaximo.toPlainString() + ")";
        }
        if (monto.remainder(multiploBillete).signum() != 0) {
            return "El cajero solo entrega multiplos de " + multiploBillete.toPlainString();
        }
        return null;
    }
}
