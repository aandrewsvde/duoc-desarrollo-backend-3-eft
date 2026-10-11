package com.duoc.bancoxyz.cuentas.web;

import com.duoc.bancoxyz.cuentas.dominio.CambioDeEstado;
import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudApertura;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudCambioEstado;
import com.duoc.bancoxyz.cuentas.servicio.CicloDeVidaCuentas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Apertura, cierre y mantenimiento de cuentas.
 *
 * <p>Va separado de {@code CuentaControlador}, que solo lee, porque son dos
 * superficies con riesgos distintos: las lecturas las hace cualquier canal
 * continuamente, mientras que estas operaciones cambian la existencia de una
 * cuenta y exigen un alcance que la mayoria de los canales no tiene.</p>
 */
@RestController
@RequestMapping("/api/cuentas")
@Tag(name = "Ciclo de vida", description = "Apertura, bloqueo y cierre de cuentas")
public class CicloDeVidaControlador {

    private final CicloDeVidaCuentas cicloDeVida;

    public CicloDeVidaControlador(CicloDeVidaCuentas cicloDeVida) {
        this.cicloDeVida = cicloDeVida;
    }

    /**
     * Quien pidio la operacion, para la bitacora.
     *
     * <p>Sale del token, NO del cuerpo de la peticion. Si el solicitante se
     * declarara a si mismo, la bitacora registraria lo que cada quien quisiera
     * escribir y dejaria de servir para auditar.</p>
     */
    private static String solicitante(Jwt token) {
        if (token == null) {
            return "desconocido";
        }
        String sujeto = token.getSubject();
        return sujeto == null ? "desconocido" : sujeto;
    }

    @PostMapping
    @Operation(summary = "Abre una cuenta nueva verificando al titular en ms-clientes")
    public ResponseEntity<Cuenta> abrir(@Valid @RequestBody SolicitudApertura solicitud,
                                        @AuthenticationPrincipal Jwt token) {
        Cuenta cuenta = cicloDeVida.abrir(solicitud, solicitante(token));
        // 201 con Location: aqui el recurso SI existe ya y en la direccion que
        // se indica, a diferencia de un pago, que responde 202 porque todavia
        // esta por resolverse.
        return ResponseEntity
                .created(URI.create("/api/cuentas/" + cuenta.numeroCuenta()))
                .body(cuenta);
    }

    @PatchMapping("/{numeroCuenta}/estado")
    @Operation(summary = "Bloquea, desbloquea o cierra una cuenta")
    public Cuenta cambiarEstado(@PathVariable int numeroCuenta,
                                @Valid @RequestBody SolicitudCambioEstado solicitud,
                                @AuthenticationPrincipal Jwt token) {
        return cicloDeVida.cambiarEstado(numeroCuenta, solicitud, solicitante(token));
    }

    @GetMapping("/{numeroCuenta}/bitacora")
    @Operation(summary = "Historial de cambios de estado de la cuenta")
    public Map<String, Object> bitacora(@PathVariable int numeroCuenta,
                                        @RequestParam(defaultValue = "20") int limite) {
        List<CambioDeEstado> cambios = cicloDeVida.bitacora(numeroCuenta, limite);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("cuenta", numeroCuenta);
        cuerpo.put("total", cambios.size());
        cuerpo.put("cambios", cambios);
        return cuerpo;
    }

    @GetMapping("/de-cliente/{clienteId}")
    @Operation(summary = "Cuentas de un cliente")
    public Map<String, Object> deCliente(@PathVariable int clienteId,
                                         @RequestParam(defaultValue = "20") int limite) {
        List<Cuenta> cuentas = cicloDeVida.deCliente(clienteId, limite);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clienteId", clienteId);
        cuerpo.put("total", cuentas.size());
        cuerpo.put("cuentas", cuentas);
        return cuerpo;
    }
}
