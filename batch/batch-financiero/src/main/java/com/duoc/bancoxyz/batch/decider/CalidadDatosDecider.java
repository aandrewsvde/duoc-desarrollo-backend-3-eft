package com.duoc.bancoxyz.batch.decider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;

/**
 * Politica de finalizacion del job basada en la calidad de la carga.
 *
 * <p>Tras el step particionado, mide que proporcion de los registros de
 * entrada llego efectivamente a la base y decide si tiene sentido
 * continuar consolidando:</p>
 * <ul>
 *   <li>{@code CALIDAD_OK}: el flujo sigue hacia los steps de agregacion
 *       e informe;</li>
 *   <li>{@code CALIDAD_INSUFICIENTE}: el flujo se desvia a la rama de
 *       alerta y el job termina en FAILED, sin publicar resumenes
 *       calculados sobre un archivo demasiado degradado.</li>
 * </ul>
 *
 * <p>El denominador es {@code leidos + omitidos_en_lectura}, no
 * {@code leidos}: las filas descartadas al leer nunca entran en
 * {@code readCount}, y omitirlas del calculo daria una calidad
 * artificialmente alta justo cuando el archivo esta peor.</p>
 */
public class CalidadDatosDecider implements JobExecutionDecider {

    public static final String CALIDAD_OK = "CALIDAD_OK";
    public static final String CALIDAD_INSUFICIENTE = "CALIDAD_INSUFICIENTE";

    /** Clave con la que se publica la metrica para los steps posteriores. */
    public static final String CLAVE_CALIDAD = "proporcionRegistrosValidos";

    private static final Logger log = LoggerFactory.getLogger(CalidadDatosDecider.class);

    private final String nombreStepDeCarga;
    private final double umbral;

    public CalidadDatosDecider(String nombreStepDeCarga, double umbral) {
        this.nombreStepDeCarga = nombreStepDeCarga;
        this.umbral = umbral;
    }

    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
        StepExecution carga = jobExecution.getStepExecutions().stream()
                .filter(se -> nombreStepDeCarga.equals(se.getStepName()))
                .findFirst()
                .orElse(null);

        if (carga == null) {
            log.warn("No se encontro el step de carga '{}'; el flujo continua por precaucion.", nombreStepDeCarga);
            return new FlowExecutionStatus(CALIDAD_OK);
        }

        long entrada = carga.getReadCount() + carga.getReadSkipCount();
        long validos = carga.getWriteCount();
        long rechazados = entrada - validos;
        double proporcion = entrada == 0 ? 0d : (double) validos / entrada;

        jobExecution.getExecutionContext().putDouble(CLAVE_CALIDAD, proporcion);

        log.info("Calidad de la carga '{}': entrada={} validos={} rechazados={} -> {}% (umbral {}%)",
                nombreStepDeCarga, entrada, validos, rechazados,
                String.format("%.2f", proporcion * 100), String.format("%.2f", umbral * 100));

        if (proporcion < umbral) {
            log.error("Proporcion de registros validos bajo el umbral: el flujo se desvia a la rama de alerta.");
            return new FlowExecutionStatus(CALIDAD_INSUFICIENTE);
        }
        return new FlowExecutionStatus(CALIDAD_OK);
    }
}
