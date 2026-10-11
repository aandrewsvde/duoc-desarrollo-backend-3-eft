package com.duoc.bancoxyz.batch.listener;

import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Instrumentacion por step y por particion. Con el step de carga
 * particionado, este listener se ejecuta una vez por particion, de modo
 * que el log muestra el reparto real de trabajo entre hilos: es la
 * evidencia de que el paralelismo esta ocurriendo y de si el balanceo es
 * parejo.
 */
@Component
public class MetricasStepListener implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(MetricasStepListener.class);

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info("[{}] inicia en el hilo '{}'", stepExecution.getStepName(), Thread.currentThread().getName());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // En afterStep el endTime todavia no esta fijado: Spring Batch lo
        // establece despues de notificar a los listeners. Se toma el instante
        // actual como cierre para no reportar siempre 0 ms.
        long duracion = stepExecution.getStartTime() == null
                ? 0L
                : Duration.between(stepExecution.getStartTime(),
                        stepExecution.getEndTime() == null ? LocalDateTime.now() : stepExecution.getEndTime())
                    .toMillis();

        log.info("[{}] termina en '{}' | leidos={} escritos={} omitidos(lect/proc/escr)={}/{}/{} "
                        + "commits={} rollbacks={} duracion={}ms estado={}",
                stepExecution.getStepName(),
                Thread.currentThread().getName(),
                stepExecution.getReadCount(),
                stepExecution.getWriteCount(),
                stepExecution.getReadSkipCount(),
                stepExecution.getProcessSkipCount(),
                stepExecution.getWriteSkipCount(),
                stepExecution.getCommitCount(),
                stepExecution.getRollbackCount(),
                duracion,
                stepExecution.getStatus());

        return stepExecution.getExitStatus();
    }
}
