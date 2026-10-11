package com.duoc.bancoxyz.batch.writer;

import com.duoc.bancoxyz.batch.support.Textos;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceso a la tabla de cuarentena {@code registro_rechazado}.
 *
 * <p>Escribe en una transaccion <b>nueva e independiente</b>: los callbacks
 * de omision se disparan alrededor de un chunk que Spring Batch acaba de
 * revertir, y si el registro de cuarentena compartiera esa transaccion se
 * perderia justo la evidencia del error que se quiere conservar.</p>
 *
 * <p>La insercion es <b>por lotes</b>. Con archivos legacy donde mas de la
 * mitad de las filas se rechaza, una transaccion por registro significa
 * cientos de commits por particion, cada uno con su sincronizacion a disco:
 * medido sobre estos mismos archivos, ese detalle dominaba el tiempo del
 * step por encima del trabajo util. Agrupar los rechazos en un solo
 * {@code batchUpdate} lo convierte en un costo marginal.</p>
 */
@Repository
public class CuarentenaDao {

    private static final String SQL = """
            INSERT INTO registro_rechazado
                (job_execution_id, job_name, step_name, fase, linea_origen, motivo, excepcion, hilo)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaccionIndependiente;

    public CuarentenaDao(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transaccionIndependiente = new TransactionTemplate(transactionManager);
        this.transaccionIndependiente.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void registrarLote(List<RegistroRechazado> registros) {
        if (registros.isEmpty()) {
            return;
        }
        transaccionIndependiente.executeWithoutResult(estado ->
                jdbcTemplate.batchUpdate(SQL, registros, registros.size(), (ps, r) -> {
                    ps.setObject(1, r.jobExecutionId());
                    ps.setString(2, Textos.acotar(r.jobName(), 80));
                    ps.setString(3, Textos.acotar(r.stepName(), 120));
                    ps.setString(4, r.fase());
                    ps.setString(5, r.lineaOrigen());
                    ps.setString(6, Textos.acotar(r.motivo(), 300));
                    ps.setString(7, Textos.acotar(r.excepcion(), 200));
                    ps.setString(8, Textos.acotar(r.hilo(), 60));
                }));
    }
}
