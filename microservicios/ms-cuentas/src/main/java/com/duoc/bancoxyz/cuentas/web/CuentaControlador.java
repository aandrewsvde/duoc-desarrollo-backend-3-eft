package com.duoc.bancoxyz.cuentas.web;

import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.Movimiento;
import com.duoc.bancoxyz.cuentas.servicio.CuentaServicio;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de consulta de cuentas.
 *
 * <p>Es deliberadamente de solo lectura. La unica via para modificar un saldo es
 * publicar un pago, y eso le corresponde a ms-pagos: exponer
 * tambien un PUT aqui crearia un segundo camino de escritura que la saga no
 * controlaria.</p>
 */
@RestController
@RequestMapping("/api/cuentas")
@Tag(name = "Cuentas", description = "Consulta de cuentas, saldos y movimientos")
public class CuentaControlador {

    private static final Logger log = LoggerFactory.getLogger(CuentaControlador.class);

    private final CuentaServicio servicio;
    private final String instancia;

    public CuentaControlador(CuentaServicio servicio,
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
        this.instancia = "ms-cuentas:" + direccion + ":" + puerto;
    }

    @GetMapping
    @Operation(summary = "Lista las cuentas registradas")
    public Map<String, Object> listar(@RequestParam(defaultValue = "50") int limite) {
        List<Cuenta> cuentas = servicio.listar(limite);
        return Map.of("instancia", instancia, "total", cuentas.size(), "cuentas", cuentas);
    }

    @GetMapping("/{numeroCuenta}")
    @Operation(summary = "Obtiene una cuenta con su saldo vigente")
    public Cuenta obtener(@PathVariable int numeroCuenta) {
        log.debug("Consulta de la cuenta {}", numeroCuenta);
        return servicio.obtener(numeroCuenta);
    }

    @GetMapping("/{numeroCuenta}/movimientos")
    @Operation(summary = "Movimientos de la cuenta, del mas reciente al mas antiguo")
    public Map<String, Object> movimientos(@PathVariable int numeroCuenta,
                                           @RequestParam(defaultValue = "20") int limite) {
        List<Movimiento> movimientos = servicio.movimientos(numeroCuenta, limite);
        return Map.of("instancia", instancia,
                "cuenta", numeroCuenta,
                "total", movimientos.size(),
                "movimientos", movimientos);
    }
}
