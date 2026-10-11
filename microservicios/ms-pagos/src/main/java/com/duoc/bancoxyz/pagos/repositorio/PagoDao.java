package com.duoc.bancoxyz.pagos.repositorio;

import com.duoc.bancoxyz.pagos.dominio.EstadoPago;
import com.duoc.bancoxyz.pagos.dominio.Pago;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Acceso a la tabla de pagos. */
@Repository
public class PagoDao {

    private final JdbcTemplate jdbc;

    public PagoDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Pago> MAPEADOR = (ResultSet rs, int fila) -> {
        Timestamp resuelta = rs.getTimestamp("resuelta_en");
        return new Pago(
                rs.getString("id"),
                rs.getInt("cuenta_id"),
                rs.getString("tipo"),
                rs.getBigDecimal("monto"),
                rs.getString("moneda"),
                rs.getString("canal"),
                EstadoPago.valueOf(rs.getString("estado")),
                rs.getString("motivo"),
                rs.getBigDecimal("saldo_anterior"),
                rs.getBigDecimal("saldo_nuevo"),
                rs.getString("referencia"),
                rs.getTimestamp("creada_en").toInstant(),
                resuelta == null ? null : resuelta.toInstant());
    };

    private static final String COLUMNAS = """
            id, cuenta_id, tipo, monto, moneda, canal, estado, motivo,
            saldo_anterior, saldo_nuevo, referencia, creada_en, resuelta_en
            """;

    /**
     * Inserta el pago como PENDIENTE.
     *
     * <p>La insercion ocurre <b>antes</b> de publicar el evento, y no despues.
     * Si el orden fuera el inverso y el proceso muriera en medio, existiria un
     * evento en Kafka para un pago que no esta registrada en ninguna
     * parte y nadie podria confirmarla ni compensarla.</p>
     */
    public void insertarPendiente(Pago pago) {
        jdbc.update("""
                INSERT INTO pago (id, cuenta_id, tipo, monto, moneda, canal, estado, referencia)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDIENTE', ?)
                """,
                pago.id(), pago.cuentaId(), pago.tipo(), pago.monto(),
                pago.moneda(), pago.canal(), pago.referencia());
    }

    public Optional<Pago> buscar(String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNAS + " FROM pago WHERE id = ?", MAPEADOR, id));
        } catch (EmptyResultDataAccessException sinFilas) {
            return Optional.empty();
        }
    }

    public Optional<Pago> buscarPorReferencia(String referencia) {
        List<Pago> encontradas = jdbc.query(
                "SELECT " + COLUMNAS + " FROM pago WHERE referencia = ?", MAPEADOR, referencia);
        return encontradas.isEmpty() ? Optional.empty() : Optional.of(encontradas.get(0));
    }

    public List<Pago> listar(int limite) {
        return jdbc.query("SELECT " + COLUMNAS + """
                 FROM pago
                ORDER BY creada_en DESC
                LIMIT ?
                """, MAPEADOR, limite);
    }

    public List<Pago> listarDeCuenta(int cuentaId, int limite) {
        return jdbc.query("SELECT " + COLUMNAS + """
                 FROM pago
                WHERE cuenta_id = ?
                ORDER BY creada_en DESC
                LIMIT ?
                """, MAPEADOR, cuentaId, limite);
    }

    /**
     * Confirma el pago con el saldo que informo ms-cuentas.
     *
     * <p>La condicion {@code estado = 'PENDIENTE'} impide que un evento tardio
     * reabra algo ya resuelto: si llega una confirmacion para un pago
     * que ya se compenso, la actualizacion no afecta ninguna fila.</p>
     */
    public int confirmar(String id, BigDecimal saldoAnterior, BigDecimal saldoNuevo) {
        return jdbc.update("""
                UPDATE pago
                   SET estado = 'CONFIRMADA', saldo_anterior = ?, saldo_nuevo = ?,
                       resuelta_en = now(), motivo = NULL
                 WHERE id = ? AND estado = 'PENDIENTE'
                """, saldoAnterior, saldoNuevo, id);
    }

    public int compensar(String id, String motivo) {
        return jdbc.update("""
                UPDATE pago
                   SET estado = 'COMPENSADA', motivo = ?, resuelta_en = now()
                 WHERE id = ? AND estado = 'PENDIENTE'
                """, motivo, id);
    }

    public List<Object[]> conteoPorEstado() {
        return jdbc.query("SELECT estado, count(*) AS cuantas FROM pago GROUP BY estado ORDER BY estado",
                (rs, fila) -> new Object[]{rs.getString("estado"), rs.getLong("cuantas")});
    }
}
