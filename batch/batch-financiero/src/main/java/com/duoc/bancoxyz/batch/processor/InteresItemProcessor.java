package com.duoc.bancoxyz.batch.processor;

import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import com.duoc.bancoxyz.batch.domain.CuentaInteres;
import com.duoc.bancoxyz.batch.domain.DatoInvalidoException;
import com.duoc.bancoxyz.batch.domain.InteresCrudo;
import com.duoc.bancoxyz.batch.support.Textos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Transformaciones y validaciones del proceso "Calculo de intereses
 * mensuales": valida la cuenta, aplica la tasa correspondiente a su tipo y
 * deja calculado el saldo final.
 *
 * <p>Reglas aplicadas sobre {@code intereses_trimestrales.csv}:</p>
 * <ul>
 *   <li>saldo presente y positivo (el archivo trae saldos vacios);</li>
 *   <li>edad dentro de un rango realista y con capacidad legal
 *       (descarta valores como 150);</li>
 *   <li>tipo de cuenta dentro del catalogo (descarta {@code -1} y
 *       {@code unknown}, que el sistema legacy usaba como relleno);</li>
 *   <li>titular identificado (descarta el marcador {@code Unknown});</li>
 *   <li>interes calculado con la tasa mensual parametrizada del tipo de
 *       cuenta y redondeo bancario a 2 decimales.</li>
 * </ul>
 *
 * <p>Los registros duplicados exactos no se filtran aqui sino en el step
 * {@code deduplicacionInteresesStep}: la deteccion de duplicados requiere
 * memoria compartida entre particiones, y mantener estado en un
 * {@code ItemProcessor} que corre en paralelo y puede reprocesar items
 * tras una reversion de chunk daria resultados no deterministas.</p>
 */
@Component
public class InteresItemProcessor implements ItemProcessor<InteresCrudo, CuentaInteres> {

    private static final Logger log = LoggerFactory.getLogger(InteresItemProcessor.class);

    private final BancoBatchProperties propiedades;

    public InteresItemProcessor(BancoBatchProperties propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public CuentaInteres process(InteresCrudo cruda) {
        BancoBatchProperties.Reglas reglas = propiedades.getReglas();
        String linea = cruda.lineaOrigen();

        if (cruda.cuentaId() == null) {
            throw new DatoInvalidoException("Identificador de cuenta ausente", linea);
        }

        BigDecimal saldo = cruda.saldo();
        if (saldo == null) {
            log.warn("Saldo ausente en la cuenta {}", cruda.cuentaId());
            throw new DatoInvalidoException("Saldo ausente: no es posible calcular intereses", linea);
        }
        if (saldo.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DatoInvalidoException(
                    "Saldo no positivo (" + saldo.toPlainString() + "): fuera del dominio del calculo", linea);
        }

        Integer edad = cruda.edad();
        if (edad == null) {
            throw new DatoInvalidoException("Edad ausente", linea);
        }
        if (edad < reglas.getEdadMinima() || edad > reglas.getEdadMaxima()) {
            log.warn("Edad fuera de rango en la cuenta {}: {}", cruda.cuentaId(), edad);
            throw new DatoInvalidoException(
                    "Edad fuera de rango (" + edad + "); admitido: "
                            + reglas.getEdadMinima() + "-" + reglas.getEdadMaxima(), linea);
        }

        String tipoCuenta = Textos.canonico(cruda.tipo());
        if (!reglas.getTiposCuentaValidos().contains(tipoCuenta)) {
            log.warn("Tipo de cuenta fuera de catalogo en la cuenta {}: '{}'", cruda.cuentaId(), cruda.tipo());
            throw new DatoInvalidoException(
                    "Tipo de cuenta fuera de catalogo ('" + cruda.tipo() + "'); admitidos: "
                            + String.join(", ", reglas.getTiposCuentaValidos()), linea);
        }

        String nombre = cruda.nombre() == null ? "" : cruda.nombre().trim();
        if (nombre.isEmpty() || reglas.getNombresInvalidos().contains(Textos.canonico(nombre))) {
            throw new DatoInvalidoException(
                    "Titular no identificado ('" + nombre + "')", linea);
        }

        BigDecimal tasa = propiedades.getTasas().para(tipoCuenta);
        BigDecimal saldoInicial = saldo.setScale(2, RoundingMode.HALF_UP);
        BigDecimal interes = saldoInicial.multiply(tasa).setScale(2, RoundingMode.HALF_UP);
        BigDecimal saldoFinal = saldoInicial.add(interes).setScale(2, RoundingMode.HALF_UP);

        return new CuentaInteres(
                cruda.cuentaId(),
                Textos.acotar(nombre, 120),
                tipoCuenta,
                edad,
                saldoInicial,
                tasa,
                interes,
                saldoFinal,
                propiedades.getTasas().getPeriodo());
    }
}
