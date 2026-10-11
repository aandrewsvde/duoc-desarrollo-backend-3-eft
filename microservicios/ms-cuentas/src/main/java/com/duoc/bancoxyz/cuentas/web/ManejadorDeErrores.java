package com.duoc.bancoxyz.cuentas.web;

import com.duoc.bancoxyz.cuentas.cliente.ClienteNoExisteException;
import com.duoc.bancoxyz.cuentas.cliente.ClientesNoDisponibleException;
import com.duoc.bancoxyz.cuentas.servicio.CambioDeEstadoInvalidoException;
import com.duoc.bancoxyz.cuentas.servicio.CuentaNoEncontradaException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones del dominio a respuestas HTTP con significado.
 *
 * <p>El codigo de estado es parte del contrato y tiene consecuencias: un 500
 * donde corresponde un 409 hace que quien llama reintente una operacion que
 * nunca va a prosperar, y que el cortocircuito del otro lado cuente como averia
 * algo que es una regla de negocio.</p>
 *
 * <p>Ningun mensaje incluye rutas internas, nombres de tabla ni trazas: lo que
 * se devuelve al exterior describe el problema de negocio, no la implementacion
 * que lo produjo.</p>
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(CuentaNoEncontradaException.class)
    public ResponseEntity<Map<String, Object>> noEncontrada(CuentaNoEncontradaException e) {
        return respuesta(HttpStatus.NOT_FOUND, "Cuenta no encontrada", e.getMessage());
    }

    /**
     * Cliente inexistente: 422 y no 404.
     *
     * <p>La ruta existe y el recurso que se pidio crear -la cuenta- es el que no
     * se pudo procesar. Un 404 le diria al canal que el endpoint no existe, que
     * es una pista equivocada para quien depura.</p>
     */
    @ExceptionHandler(ClienteNoExisteException.class)
    public ResponseEntity<Map<String, Object>> clienteInexistente(ClienteNoExisteException e) {
        return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, "Cliente inexistente", e.getMessage());
    }

    /**
     * Regla de negocio que impide el cambio: 409.
     *
     * <p>Cerrar una cuenta con saldo o reabrir una cerrada no son errores del
     * solicitante ni averias del servicio: son estados en conflicto. Reintentar
     * sin cambiar nada daria el mismo resultado.</p>
     */
    @ExceptionHandler(CambioDeEstadoInvalidoException.class)
    public ResponseEntity<Map<String, Object>> conflicto(CambioDeEstadoInvalidoException e) {
        return respuesta(HttpStatus.CONFLICT, "Operacion no permitida", e.getMessage());
    }

    /**
     * ms-clientes no respondio: 503 y la cuenta NO se abrio.
     *
     * <p>Es un fallo temporal, de modo que el canal puede y debe reintentar. Se
     * registra el detalle tecnico en el log, no en la respuesta.</p>
     */
    @ExceptionHandler(ClientesNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> dependenciaCaida(ClientesNoDisponibleException e) {
        log.error("No se pudo verificar el titular contra ms-clientes", e);
        return respuesta(HttpStatus.SERVICE_UNAVAILABLE, "Verificacion no disponible",
                e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> datosInvalidos(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respuesta(HttpStatus.BAD_REQUEST, "Solicitud invalida", detalle);
    }

    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String error,
                                                          String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("estado", estado.value());
        cuerpo.put("error", error);
        cuerpo.put("mensaje", mensaje);
        cuerpo.put("instante", Instant.now().toString());
        return ResponseEntity.status(estado).body(cuerpo);
    }
}
