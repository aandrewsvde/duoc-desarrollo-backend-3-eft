package com.duoc.bancoxyz.pagos.web;

import com.duoc.bancoxyz.pagos.cliente.RecursoNoExisteException;
import com.duoc.bancoxyz.pagos.mensajeria.PublicadorDeEventos;
import com.duoc.bancoxyz.pagos.servicio.PagoNoEncontradoException;
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

/** Traduce las excepciones del dominio a respuestas HTTP con significado. */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(PagoNoEncontradoException.class)
    public ResponseEntity<Map<String, Object>> noEncontrada(PagoNoEncontradoException e) {
        return respuesta(HttpStatus.NOT_FOUND, "Pago no encontrada", e.getMessage());
    }

    /**
     * Cuenta inexistente: 422 y no 404.
     *
     * <p>El recurso que se pidio crear -el pago- es el que no se pudo
     * procesar; la ruta existe. Un 404 aqui le diria al canal que el endpoint no
     * existe, que es una pista equivocada para quien depura.</p>
     */
    @ExceptionHandler(RecursoNoExisteException.class)
    public ResponseEntity<Map<String, Object>> cuentaInexistente(RecursoNoExisteException e) {
        return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, "Cuenta inexistente", e.getMessage());
    }

    /**
     * No se pudo dejar el evento en Kafka: 503 y el pago quedo revertida.
     *
     * <p>Es un fallo temporal de infraestructura, de modo que el canal puede y
     * debe reintentar con la misma referencia; la idempotencia garantiza que no
     * se duplique si el primer intento si habia llegado.</p>
     */
    @ExceptionHandler(PublicadorDeEventos.PublicacionFallidaException.class)
    public ResponseEntity<Map<String, Object>> publicacionFallida(
            PublicadorDeEventos.PublicacionFallidaException e) {
        log.error("El pago se revirtio porque no se pudo publicar el evento", e);
        return respuesta(HttpStatus.SERVICE_UNAVAILABLE, "Mensajeria no disponible",
                e.getMessage() + " El pago no quedo registrada; reintente con la misma referencia.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> datosInvalidos(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respuesta(HttpStatus.BAD_REQUEST, "Solicitud invalida", detalle);
    }

    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String error, String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("estado", estado.value());
        cuerpo.put("error", error);
        cuerpo.put("mensaje", mensaje);
        cuerpo.put("instante", Instant.now().toString());
        return ResponseEntity.status(estado).body(cuerpo);
    }
}
