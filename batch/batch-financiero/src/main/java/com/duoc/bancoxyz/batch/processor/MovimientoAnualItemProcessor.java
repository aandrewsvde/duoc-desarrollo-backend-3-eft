package com.duoc.bancoxyz.batch.processor;

import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import com.duoc.bancoxyz.batch.domain.DatoInvalidoException;
import com.duoc.bancoxyz.batch.domain.MovimientoAnual;
import com.duoc.bancoxyz.batch.domain.MovimientoCrudo;
import com.duoc.bancoxyz.batch.support.NormalizadorFechas;
import com.duoc.bancoxyz.batch.support.Textos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Transformaciones y validaciones del proceso "Generacion de estados de
 * cuenta anuales".
 *
 * <p>Ademas de validar, este procesador <b>reclasifica</b> el movimiento:
 * el archivo legacy guarda el tipo de operacion como texto libre y con
 * acentuacion inconsistente ({@code deposito} / {@code deposito} con
 * tilde), y no distingue abonos de cargos. Aqui se canoniza el tipo y se
 * le asigna el signo contable, que es lo que despues permite cuadrar el
 * estado de cuenta.</p>
 *
 * <p>Reglas aplicadas sobre {@code estados_financieros_anuales.csv}:</p>
 * <ul>
 *   <li>monto presente y positivo; el signo lo determina el tipo de
 *       operacion, no el archivo (que trae montos negativos mal grabados);</li>
 *   <li>fecha normalizada a ISO-8601 desde los cuatro formatos legacy;</li>
 *   <li>fecha dentro del ejercicio anual esperado;</li>
 *   <li>tipo de operacion dentro del catalogo, sin acentos y en minusculas;</li>
 *   <li>descripcion faltante completada con un valor por defecto, para no
 *       perder el movimiento por un campo no critico.</li>
 * </ul>
 */
@Component
public class MovimientoAnualItemProcessor implements ItemProcessor<MovimientoCrudo, MovimientoAnual> {

    private static final Logger log = LoggerFactory.getLogger(MovimientoAnualItemProcessor.class);

    /** Operaciones que abonan a la cuenta; el resto se registra como cargo. */
    private static final Set<String> ABONOS = Set.of("deposito");

    private final BancoBatchProperties propiedades;

    public MovimientoAnualItemProcessor(BancoBatchProperties propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public MovimientoAnual process(MovimientoCrudo cruda) {
        BancoBatchProperties.Reglas reglas = propiedades.getReglas();
        String linea = cruda.lineaOrigen();

        if (cruda.cuentaId() == null) {
            throw new DatoInvalidoException("Identificador de cuenta ausente", linea);
        }

        BigDecimal monto = cruda.monto();
        if (monto == null) {
            log.warn("Monto ausente en un movimiento de la cuenta {}", cruda.cuentaId());
            throw new DatoInvalidoException("Monto ausente: el movimiento no puede valorizarse", linea);
        }
        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Monto no positivo en un movimiento de la cuenta {}: {}", cruda.cuentaId(), monto);
            throw new DatoInvalidoException(
                    "Monto no positivo (" + monto.toPlainString()
                            + "): el signo lo define el tipo de operacion, no el importe", linea);
        }

        NormalizadorFechas.FechaNormalizada fecha = NormalizadorFechas.normalizar(cruda.fecha());
        if (fecha == null) {
            log.warn("Fecha ilegible en un movimiento de la cuenta {}: '{}'", cruda.cuentaId(), cruda.fecha());
            throw new DatoInvalidoException(
                    "Fecha invalida ('" + cruda.fecha() + "'); formatos admitidos: "
                            + NormalizadorFechas.formatosAceptados(), linea);
        }
        if (fecha.fecha().getYear() != reglas.getAnioEsperado()) {
            throw new DatoInvalidoException(
                    "Fecha fuera del ejercicio " + reglas.getAnioEsperado() + " (" + fecha.fecha() + ")", linea);
        }

        String tipoMovimiento = Textos.canonico(cruda.transaccion());
        if (!reglas.getTiposMovimientoValidos().contains(tipoMovimiento)) {
            throw new DatoInvalidoException(
                    "Tipo de movimiento fuera de catalogo ('" + cruda.transaccion() + "'); admitidos: "
                            + String.join(", ", reglas.getTiposMovimientoValidos()), linea);
        }

        String descripcion = cruda.descripcion() == null ? "" : cruda.descripcion().trim();
        if (descripcion.isEmpty()) {
            descripcion = reglas.getDescripcionPorDefecto();
        }

        int signo = ABONOS.contains(tipoMovimiento) ? 1 : -1;
        BigDecimal montoNormalizado = monto.setScale(2, RoundingMode.HALF_UP);

        return new MovimientoAnual(
                cruda.cuentaId(),
                fecha.fecha(),
                fecha.fecha().getYear(),
                tipoMovimiento,
                signo,
                montoNormalizado,
                montoNormalizado.multiply(BigDecimal.valueOf(signo)),
                Textos.acotar(descripcion, 200),
                cruda.fecha().trim(),
                fecha.formatoDetectado());
    }
}
