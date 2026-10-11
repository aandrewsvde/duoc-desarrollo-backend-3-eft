package com.duoc.bancoxyz.batch.migracion;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Carga en ms-pagos el historial de transacciones diarias del legado.
 *
 * <p>Entran como CONFIRMADAS y no como PENDIENTES, que es la diferencia clave:
 * son operaciones que el sistema antiguo YA ejecuto y cuyo efecto sobre el
 * saldo ya esta reflejado en los archivos de cuentas. Cargarlas como pendientes
 * haria que la saga intentara aplicarlas otra vez y el dinero se moveria dos
 * veces.</p>
 *
 * <p>El canal es WEB porque el sistema legado no registra por donde entro cada
 * operacion. Es un supuesto, y se deja escrito aqui y en el informe en lugar de
 * aparentar un dato que no existe.</p>
 */
public class MigracionPagosTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(MigracionPagosTasklet.class);

    private static final int TAMANO_LOTE = 500;

    /** Cuenta a la que se imputan las transacciones del legado. */
    private final int cuentaConcentradora;

    private final JdbcTemplate origen;
    private final JdbcTemplate destinoPagos;

    public MigracionPagosTasklet(JdbcTemplate origen, JdbcTemplate destinoPagos,
                                 int cuentaConcentradora) {
        this.origen = origen;
        this.destinoPagos = destinoPagos;
        this.cuentaConcentradora = cuentaConcentradora;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) {
        List<Map<String, Object>> transacciones = origen.queryForList("""
                SELECT id, fecha, monto, tipo
                  FROM transaccion
                 ORDER BY id
                """);

        if (transacciones.isEmpty()) {
            log.warn("No hay transacciones que migrar");
            return RepeatStatus.FINISHED;
        }

        destinoPagos.update("DELETE FROM pago WHERE referencia LIKE 'LEGACY-%'");

        List<Object[]> lote = new java.util.ArrayList<>(TAMANO_LOTE);
        int migrados = 0;

        for (Map<String, Object> fila : transacciones) {
            long id = ((Number) fila.get("id")).longValue();
            // credito abona, debito carga. El tipo del legado se traduce al
            // vocabulario del sistema nuevo en vez de arrastrarlo: si se
            // guardara "credito", ms-pagos tendria que entender dos idiomas.
            String tipoLegado = ((String) fila.get("tipo")).toLowerCase();
            String tipo = "credito".equals(tipoLegado) ? "DEPOSITO" : "PAGO";

            lote.add(new Object[]{
                    "LG-" + id,
                    cuentaConcentradora,
                    tipo,
                    fila.get("monto"),
                    "CLP",
                    "WEB",
                    "LEGACY-" + id,
                    fila.get("fecha")});

            if (lote.size() == TAMANO_LOTE) {
                migrados += insertar(lote);
                lote.clear();
            }
        }
        if (!lote.isEmpty()) {
            migrados += insertar(lote);
        }

        log.info("Transacciones diarias migradas a ms-pagos: {} (imputadas a la cuenta {})",
                migrados, cuentaConcentradora);
        contribucion.incrementWriteCount(migrados);
        return RepeatStatus.FINISHED;
    }

    private int insertar(List<Object[]> lote) {
        destinoPagos.batchUpdate("""
                INSERT INTO pago (id, cuenta_id, tipo, monto, moneda, canal, estado,
                                  referencia, creada_en, resuelta_en)
                VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMADA', ?, ?, ?)
                """, lote.stream()
                        .map(f -> new Object[]{f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[7]})
                        .toList());
        return lote.size();
    }
}
