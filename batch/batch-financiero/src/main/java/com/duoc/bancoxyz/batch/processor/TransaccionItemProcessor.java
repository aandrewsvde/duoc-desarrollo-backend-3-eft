package com.duoc.bancoxyz.batch.processor;

import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import com.duoc.bancoxyz.batch.domain.DatoInvalidoException;
import com.duoc.bancoxyz.batch.domain.Transaccion;
import com.duoc.bancoxyz.batch.domain.TransaccionCruda;
import com.duoc.bancoxyz.batch.support.NormalizadorFechas;
import com.duoc.bancoxyz.batch.support.Textos;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Transformaciones y validaciones del proceso "Reporte de transacciones
 * diarias".
 *
 * <p>El procesador es <b>sin estado</b> a proposito: se ejecuta en paralelo
 * en todos los hilos de particion y Spring Batch puede reprocesar un item
 * cuando un chunk se revierte por una omision. Cualquier estado compartido
 * aqui produciria resultados distintos entre ejecuciones.</p>
 *
 * <p>Reglas aplicadas sobre {@code movimientos_financieros_diarios.csv}:</p>
 * <ul>
 *   <li>identificador presente;</li>
 *   <li>monto presente y estrictamente positivo (el archivo trae montos
 *       vacios, cero y negativos);</li>
 *   <li>tipo dentro del catalogo vigente (descarta {@code invalid} y
 *       {@code desconocido});</li>
 *   <li>fecha normalizada a ISO-8601 desde cualquiera de los cuatro
 *       formatos legacy, rechazando fechas imposibles como {@code 2024-13-01};</li>
 *   <li>fecha dentro del ejercicio contable esperado.</li>
 * </ul>
 */
@Component
public class TransaccionItemProcessor implements ItemProcessor<TransaccionCruda, Transaccion> {

    private static final Logger log = LoggerFactory.getLogger(TransaccionItemProcessor.class);

    private final BancoBatchProperties propiedades;

    public TransaccionItemProcessor(BancoBatchProperties propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public Transaccion process(TransaccionCruda cruda) {
        BancoBatchProperties.Reglas reglas = propiedades.getReglas();
        String linea = cruda.lineaOrigen();

        if (cruda.id() == null) {
            throw new DatoInvalidoException("Identificador de transaccion ausente", linea);
        }

        BigDecimal monto = cruda.monto();
        if (monto == null) {
            log.warn("Monto ausente en la transaccion {}", cruda.id());
            throw new DatoInvalidoException("Monto ausente: la transaccion no puede valorizarse", linea);
        }
        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Monto no positivo para la transaccion {}: {}", cruda.id(), monto);
            throw new DatoInvalidoException(
                    "Monto no positivo (" + monto.toPlainString() + "): el importe debe ser mayor que 0", linea);
        }

        String tipo = Textos.canonico(cruda.tipo());
        if (!reglas.getTiposTransaccionValidos().contains(tipo)) {
            log.warn("Tipo de transaccion fuera de catalogo en la transaccion {}: '{}'", cruda.id(), cruda.tipo());
            throw new DatoInvalidoException(
                    "Tipo de transaccion fuera de catalogo ('" + cruda.tipo() + "'); admitidos: "
                            + String.join(", ", reglas.getTiposTransaccionValidos()), linea);
        }

        NormalizadorFechas.FechaNormalizada fecha = NormalizadorFechas.normalizar(cruda.fecha());
        if (fecha == null) {
            log.warn("Fecha ilegible o inexistente en la transaccion {}: '{}'", cruda.id(), cruda.fecha());
            throw new DatoInvalidoException(
                    "Fecha invalida ('" + cruda.fecha() + "'); formatos admitidos: "
                            + NormalizadorFechas.formatosAceptados(), linea);
        }
        if (fecha.fecha().getYear() != reglas.getAnioEsperado()) {
            throw new DatoInvalidoException(
                    "Fecha fuera del ejercicio " + reglas.getAnioEsperado() + " (" + fecha.fecha() + ")", linea);
        }

        return new Transaccion(
                cruda.id(),
                fecha.fecha(),
                monto.setScale(2, java.math.RoundingMode.HALF_UP),
                tipo,
                cruda.fecha().trim(),
                fecha.formatoDetectado());
    }
}
