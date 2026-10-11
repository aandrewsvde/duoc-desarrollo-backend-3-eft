package com.duoc.bancoxyz.batch.policy;

import com.duoc.bancoxyz.batch.domain.DatoInvalidoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Politica de omision personalizada del Banco XYZ.
 *
 * <p>Separa explicitamente tres familias de error, porque tratarlas igual
 * es lo que hace fragil a un batch:</p>
 * <ol>
 *   <li><b>Defecto del dato</b> ({@link FlatFileParseException},
 *       {@link DatoInvalidoException}): se omite el registro, se deriva a
 *       cuarentena y el proceso continua. Reintentarlo no sirve de nada
 *       porque el origen no va a cambiar.</li>
 *   <li><b>Fallo de infraestructura</b> (conexion a la base caida, recurso
 *       no disponible): <b>nunca</b> se omite. Omitirlo silenciaria una
 *       perdida masiva de registros y dejaria una salida incompleta que
 *       parece correcta. El step falla y queda restartable.</li>
 *   <li><b>Cualquier otra excepcion</b>: no se omite, por prudencia. Un
 *       error no previsto debe interrumpir y ser diagnosticado, no
 *       enterrado en el log.</li>
 * </ol>
 *
 * <p>El limite es finito a proposito: si un archivo llega tan degradado
 * que supera el umbral, el job debe fallar de forma controlada en vez de
 * producir una salida parcial que nadie audita.</p>
 */
public class PoliticaOmisionPersonalizada implements SkipPolicy {

    private static final Logger log = LoggerFactory.getLogger(PoliticaOmisionPersonalizada.class);

    private final int limiteOmisiones;

    public PoliticaOmisionPersonalizada(int limiteOmisiones) {
        this.limiteOmisiones = limiteOmisiones;
    }

    @Override
    public boolean shouldSkip(Throwable excepcion, long omisionesAcumuladas) throws SkipLimitExceededException {

        if (esFalloDeInfraestructura(excepcion)) {
            log.error("Fallo de infraestructura: NO se omite y el step se detiene -> {}",
                    excepcion.getClass().getSimpleName(), excepcion);
            return false;
        }

        if (esDefectoDelDato(excepcion)) {
            if (omisionesAcumuladas >= limiteOmisiones) {
                log.error("Limite de omisiones alcanzado ({}). El job falla de forma controlada.", limiteOmisiones);
                throw new SkipLimitExceededException(limiteOmisiones, excepcion);
            }
            log.debug("Omision {} de {} -> {}", omisionesAcumuladas + 1, limiteOmisiones, excepcion.getMessage());
            return true;
        }

        log.error("Excepcion no contemplada por la politica de omision: {}",
                excepcion.getClass().getName(), excepcion);
        return false;
    }

    private boolean esDefectoDelDato(Throwable excepcion) {
        return excepcion instanceof FlatFileParseException
                || excepcion instanceof DatoInvalidoException
                || excepcion.getCause() instanceof DatoInvalidoException
                || excepcion instanceof NumberFormatException;
    }

    private boolean esFalloDeInfraestructura(Throwable excepcion) {
        return excepcion instanceof DataAccessResourceFailureException
                || excepcion instanceof CannotGetJdbcConnectionException;
    }
}
