package com.duoc.bancoxyz.clientes.web;

import com.duoc.bancoxyz.clientes.dominio.ClienteNoEncontradoException;
import com.duoc.bancoxyz.clientes.servicio.EstadoClienteInvalidoException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones del dominio a respuestas HTTP.
 *
 * <p>El 404 de aqui importa mas que en otros servicios: es lo que ms-cuentas
 * interpreta como "este cliente no existe" para rechazar una apertura sin que
 * su cortocircuito lo cuente como averia. Si este servicio respondiera 500 ante
 * un identificador desconocido, un canal insistiendo con datos equivocados
 * bastaria para dejar sin servicio la apertura de cuentas.</p>
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    @ExceptionHandler(ClienteNoEncontradoException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(ClienteNoEncontradoException e) {
        return respuesta(HttpStatus.NOT_FOUND, "Cliente no encontrado", e.getMessage());
    }

    @ExceptionHandler(EstadoClienteInvalidoException.class)
    public ResponseEntity<Map<String, Object>> conflicto(EstadoClienteInvalidoException e) {
        return respuesta(HttpStatus.CONFLICT, "Operacion no permitida", e.getMessage());
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
