package com.duoc.bancoxyz.bff.movil.web;

import com.duoc.bancoxyz.canal.cliente.ClienteDelNucleo;
import com.duoc.bancoxyz.canal.dto.ClienteDelCore;
import com.duoc.bancoxyz.canal.dto.CuentaDelCore;
import com.duoc.bancoxyz.canal.dto.MovimientoDelCore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canal movil: lo MINIMO que sirve.
 *
 * <p>Su objetivo es el opuesto al del canal web. En una red movil cada byte
 * cuesta bateria y tiempo, y la pantalla no da para mas de un vistazo, de modo
 * que aqui se optimiza por TAMANO:</p>
 *
 * <ul>
 *   <li>los nombres de campo son cortos ({@code n}, {@code s}, {@code t});</li>
 *   <li>el nombre del titular se abrevia a inicial y apellido;</li>
 *   <li>los importes se redondean a entero: nadie decide nada mirando los
 *       centavos en un telefono;</li>
 *   <li>los tipos viajan como una sola letra, con la tabla de equivalencia
 *       publicada una vez en la propia respuesta en lugar de repetir la palabra
 *       completa en cada movimiento;</li>
 *   <li>solo tres movimientos por cuenta.</li>
 * </ul>
 *
 * <p>Son exactamente los mismos datos del mismo nucleo que sirve el canal web.
 * Lo unico que cambia es la forma, y esa es toda la idea del patron.</p>
 */
@RestController
@RequestMapping("/canal/movil")
@Tag(name = "Canal movil", description = "Respuesta minima para aplicacion en telefono")
public class ResumenMovilControlador {

    private static final Logger log = LoggerFactory.getLogger(ResumenMovilControlador.class);

    /** Tabla de equivalencia de los codigos de una letra. */
    private static final Map<String, String> TIPOS = Map.of(
            "D", "DEPOSITO", "R", "RETIRO", "C", "COMPRA", "P", "PAGO", "T", "TRANSFERENCIA");

    private final ClienteDelNucleo nucleo;
    private final int movimientosPorCuenta;
    private final String instancia;

    public ResumenMovilControlador(ClienteDelNucleo nucleo,
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
        this.instancia = "bff-movil:" + direccion + ":" + puerto;
    }

    @GetMapping("/resumen/{clienteId}")
    @Operation(summary = "Resumen ligero del titular y sus cuentas")
    public Map<String, Object> resumen(@PathVariable int clienteId) {
        ClienteDelCore cliente = nucleo.cliente(clienteId);
        List<CuentaDelCore> cuentas = nucleo.cuentasDe(clienteId);

        List<Map<String, Object>> breve = cuentas.stream().map(cuenta -> {
            List<MovimientoDelCore> movimientos =
                    nucleo.movimientos(cuenta.numeroCuenta(), movimientosPorCuenta);

            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("c", cuenta.numeroCuenta());
            fila.put("t", abreviarTipo(cuenta.tipoCuenta()));
            fila.put("s", cuenta.saldo().setScale(0, java.math.RoundingMode.HALF_UP));
            fila.put("m", movimientos.stream().map(movimiento -> {
                Map<String, Object> mov = new LinkedHashMap<>();
                mov.put("f", movimiento.fecha().toString());
                mov.put("t", abreviarTipo(movimiento.tipo()));
                mov.put("n", movimiento.montoConSigno().setScale(0, java.math.RoundingMode.HALF_UP));
                return mov;
            }).toList());
            return fila;
        }).toList();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("canal", "MOVIL");
        respuesta.put("instancia", instancia);
        respuesta.put("n", abreviarNombre(cliente.nombre()));
        respuesta.put("seg", "PREFERENTE".equals(cliente.segmento()) ? "P" : "E");
        respuesta.put("tot", cliente.saldoTotal().setScale(0, java.math.RoundingMode.HALF_UP));
        respuesta.put("ctas", breve);
        // La tabla va una vez por respuesta, no una por movimiento. Con tres
        // cuentas y tres movimientos cada una, repetir la palabra completa
        // costaria mas que publicar el diccionario entero.
        respuesta.put("tipos", TIPOS);

        log.info("Resumen movil servido para el cliente {}", clienteId);
        return respuesta;
    }

    /**
     * "Diana Prince" -> "D. Prince".
     *
     * <p>En una pantalla de telefono el nombre completo se corta igual; es
     * mejor decidir donde se corta que dejarlo al azar del ancho disponible.</p>
     */
    private static String abreviarNombre(String nombre) {
        String[] partes = nombre.trim().split("\\s+");
        if (partes.length < 2) {
            return nombre;
        }
        return partes[0].charAt(0) + ". " + partes[partes.length - 1];
    }

    private static String abreviarTipo(String tipo) {
        return tipo == null || tipo.isBlank() ? "?" : tipo.substring(0, 1).toUpperCase();
    }
}
