package com.duoc.bancoxyz.batch.writer;

/** Una fila de cuarentena pendiente de persistir. */
public record RegistroRechazado(
        Long jobExecutionId,
        String jobName,
        String stepName,
        String fase,
        String lineaOrigen,
        String motivo,
        String excepcion,
        String hilo) {
}
