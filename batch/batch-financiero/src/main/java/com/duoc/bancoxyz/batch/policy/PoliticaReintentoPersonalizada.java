package com.duoc.bancoxyz.batch.policy;

import com.duoc.bancoxyz.batch.domain.DatoInvalidoException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.classify.Classifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Politica de reintento personalizada: clasifica la excepcion antes de
 * decidir, en lugar de reintentar todo un numero fijo de veces.
 *
 * <p>Solo se reintenta lo <b>transitorio</b>: deadlocks, perdida de lock,
 * timeouts de consulta y fallos temporales de acceso a datos, que es
 * exactamente lo que produce el particionado cuando varias particiones
 * escriben en paralelo sobre las mismas tablas. Un dato defectuoso, en
 * cambio, se marca como no reintentable: repetirlo tres veces solo
 * multiplica el trabajo y ensucia el log antes de terminar igualmente en
 * cuarentena.</p>
 *
 * <p>Se combina con un {@code ExponentialBackOffPolicy} para no golpear la
 * base de datos en rafaga cuando el problema persiste.</p>
 */
public class PoliticaReintentoPersonalizada extends ExceptionClassifierRetryPolicy {

    public PoliticaReintentoPersonalizada(int intentosMaximos) {
        Map<Class<? extends Throwable>, Boolean> reintentables = new HashMap<>();
        reintentables.put(TransientDataAccessException.class, true);
        reintentables.put(TransientDataAccessResourceException.class, true);
        reintentables.put(DeadlockLoserDataAccessException.class, true);
        reintentables.put(CannotAcquireLockException.class, true);
        reintentables.put(PessimisticLockingFailureException.class, true);
        reintentables.put(OptimisticLockingFailureException.class, true);
        reintentables.put(QueryTimeoutException.class, true);

        RetryPolicy conReintento = new SimpleRetryPolicy(intentosMaximos, reintentables, true);
        RetryPolicy sinReintento = new NeverRetryPolicy();

        Classifier<Throwable, RetryPolicy> clasificador = excepcion -> {
            if (excepcion instanceof DatoInvalidoException || excepcion instanceof FlatFileParseException) {
                return sinReintento;
            }
            return conReintento;
        };
        setExceptionClassifier(clasificador);
    }
}
