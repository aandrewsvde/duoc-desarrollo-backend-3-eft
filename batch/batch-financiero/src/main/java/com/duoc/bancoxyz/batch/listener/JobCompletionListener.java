package com.duoc.bancoxyz.batch.listener;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

/**
 * Traza del ciclo de vida del job y resumen final de ejecucion.
 *
 * <p>Al cerrar imprime una tabla con el detalle de cada step y particion
 * (leidos, escritos, omitidos por fase, commits, rollbacks y duracion).
 * Esa tabla es la evidencia de ejecucion que pide el enunciado y, sobre
 * todo, permite comprobar la identidad {@code leidos = escritos +
 * omitidos}, es decir, que ningun registro de entrada se perdio sin
 * quedar registrado.</p>
 */
@Component
public class JobCompletionListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(JobCompletionListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("==========================================================================");
        log.info("INICIO job '{}' | JobExecutionId={} | parametros={}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getId(),
                jobExecution.getJobParameters());
        log.info("==========================================================================");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        List<StepExecution> steps = jobExecution.getStepExecutions().stream()
                .sorted(Comparator.comparing(StepExecution::getStepName))
                .toList();

        log.info("--------------------------------------------------------------------------");
        log.info("RESUMEN job '{}' | estado={} | salida={}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getStatus(),
                jobExecution.getExitStatus().getExitCode());
        log.info("{}", String.format("%-42s %8s %8s %8s %8s %8s %8s %10s",
                "STEP", "LEIDOS", "ESCRITOS", "OM.LECT", "OM.PROC", "OM.ESCR", "COMMITS", "MS"));

        for (StepExecution step : steps) {
            log.info("{}", String.format("%-42s %8d %8d %8d %8d %8d %8d %10d",
                    step.getStepName(),
                    step.getReadCount(),
                    step.getWriteCount(),
                    step.getReadSkipCount(),
                    step.getProcessSkipCount(),
                    step.getWriteSkipCount(),
                    step.getCommitCount(),
                    duracionMs(step)));
        }

        log.info("--------------------------------------------------------------------------");
        cuadrarStepsDeCarga(steps);

        if (jobExecution.getStartTime() != null && jobExecution.getEndTime() != null) {
            log.info("Duracion total del job: {} ms",
                    Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis());
        }

        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info("Job '{}' COMPLETADO correctamente.", jobExecution.getJobInstance().getJobName());
        } else {
            log.error("Job '{}' finalizo con estado {}. Excepciones: {}",
                    jobExecution.getJobInstance().getJobName(),
                    jobExecution.getStatus(),
                    jobExecution.getAllFailureExceptions());
        }
        log.info("==========================================================================");
    }

    /** Los steps trabajadores de una particion se nombran {@code step:particionN}. */
    private static boolean esParticion(StepExecution step) {
        return step.getStepName().contains(":");
    }

    /**
     * Cuadre del step de carga: demuestra que ningun registro del archivo
     * de origen se perdio sin dejar rastro.
     *
     * <p>Los registros descartados al leer no entran en {@code readCount},
     * de modo que la entrada real es {@code leidos + omitidos_en_lectura}.
     * De ahi, cada registro termina persistido o en cuarentena.</p>
     *
     * <p>Se toma solo el step manager: sus contadores ya agregan los de
     * todas las particiones, y los steps de agregacion e informe releen
     * datos ya contabilizados.</p>
     */
    private static void cuadrarStepsDeCarga(List<StepExecution> steps) {
        steps.stream()
                .filter(step -> !esParticion(step) && step.getStepName().startsWith("carga"))
                .forEach(step -> {
                    long entrada = step.getReadCount() + step.getReadSkipCount();
                    long validos = step.getWriteCount();
                    long rechazados = step.getReadSkipCount() + step.getProcessSkipCount() + step.getWriteSkipCount();
                    // Un step abortado deja el archivo a medio leer: ahi el cuadre
                    // no aplica y afirmar lo contrario seria enganoso.
                    if (step.getStatus() != BatchStatus.COMPLETED) {
                        log.warn("CUADRE de {}: no aplica. El step termino en {}, con {} registros leidos y "
                                        + "{} confirmados antes de abortar; los contadores quedan incompletos. "
                                        + "Reanude el job con --restart para completarlo.",
                                step.getStepName(), step.getStatus(), entrada, validos);
                        return;
                    }
                    boolean cuadra = entrada == validos + rechazados;
                    log.info("CUADRE de {}: entrada={} = persistidos({}) + en cuarentena({}) -> {}",
                            step.getStepName(), entrada, validos, rechazados,
                            cuadra ? "OK" : "DESCUADRE, revisar");
                    if (!cuadra) {
                        log.error("El step {} no cuadra: {} de entrada frente a {} contabilizados.",
                                step.getStepName(), entrada, validos + rechazados);
                    }
                });
    }

    private static long duracionMs(StepExecution step) {
        if (step.getStartTime() == null || step.getEndTime() == null) {
            return 0L;
        }
        return Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
    }
}
