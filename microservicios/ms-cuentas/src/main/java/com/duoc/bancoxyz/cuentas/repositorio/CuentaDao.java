package com.duoc.bancoxyz.cuentas.repositorio;

import com.duoc.bancoxyz.cuentas.dominio.CambioDeEstado;
import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.Movimiento;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Acceso a las tablas propias de ms-cuentas. */
@Repository
public class CuentaDao {

    private final JdbcTemplate jdbc;

    public CuentaDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Columnas de cuenta, en un solo lugar: una lista repetida se desincroniza.
     *
     * <p>Empieza por un espacio a proposito. Se concatena justo despues de la
     * palabra SELECT, y un bloque de texto elimina la sangria comun: sin ese
     * espacio la sentencia sale como "SELECTnumero_cuenta" y falla.</p>
     */
    private static final String COLUMNAS_CUENTA =
            " numero_cuenta, cliente_id, titular, tipo_cuenta, saldo, moneda,"
            + " estado, abierta_en, cerrada_en, actualizada_en ";

    private static final RowMapper<Cuenta> MAPEADOR_CUENTA = (ResultSet rs, int fila) -> {
        java.sql.Timestamp cerrada = rs.getTimestamp("cerrada_en");
        return new Cuenta(
                rs.getInt("numero_cuenta"),
                rs.getInt("cliente_id"),
                rs.getString("titular"),
                rs.getString("tipo_cuenta"),
                rs.getBigDecimal("saldo"),
                rs.getString("moneda"),
                rs.getString("estado"),
                rs.getTimestamp("abierta_en").toInstant(),
                cerrada == null ? null : cerrada.toInstant(),
                rs.getTimestamp("actualizada_en").toInstant());
    };

    private static final RowMapper<Movimiento> MAPEADOR_MOVIMIENTO = (ResultSet rs, int fila) -> new Movimiento(
            rs.getLong("id"),
            rs.getInt("cuenta_id"),
            rs.getString("pago_id"),
            rs.getDate("fecha").toLocalDate(),
            rs.getString("tipo"),
            rs.getInt("signo"),
            rs.getBigDecimal("monto"),
            rs.getString("descripcion"),
            rs.getString("origen"));

    public List<Cuenta> listar(int limite) {
        return jdbc.query("""
                SELECT """ + COLUMNAS_CUENTA + """
                  FROM cuenta
                 ORDER BY numero_cuenta
                 LIMIT ?
                """, MAPEADOR_CUENTA, limite);
    }

    public Optional<Cuenta> buscar(int numeroCuenta) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT """ + COLUMNAS_CUENTA + """
                      FROM cuenta
                     WHERE numero_cuenta = ?
                    """, MAPEADOR_CUENTA, numeroCuenta));
        } catch (EmptyResultDataAccessException sinFilas) {
            return Optional.empty();
        }
    }

    /**
     * Lee la cuenta bloqueando su fila hasta el fin de el pago.
     *
     * <p>{@code FOR UPDATE} es lo que impide que dos pagos concurrentes
     * sobre la misma cuenta lean ambas el saldo anterior y escriban cada una su
     * resultado, perdiendo una de las dos. La segunda queda esperando en la base
     * hasta que la primera confirma, y entonces lee el saldo ya actualizado.</p>
     */
    public Optional<Cuenta> bloquearParaActualizar(int numeroCuenta) {
        List<Cuenta> encontradas = jdbc.query("""
                SELECT """ + COLUMNAS_CUENTA + """
                  FROM cuenta
                 WHERE numero_cuenta = ?
                   FOR UPDATE
                """, MAPEADOR_CUENTA, numeroCuenta);
        return encontradas.isEmpty() ? Optional.empty() : Optional.of(encontradas.get(0));
    }

    public void actualizarSaldo(int numeroCuenta, BigDecimal saldoNuevo) {
        jdbc.update("UPDATE cuenta SET saldo = ?, actualizada_en = now() WHERE numero_cuenta = ?",
                saldoNuevo, numeroCuenta);
    }

    public void insertarMovimiento(int cuentaId, String pagoId, LocalDate fecha,
                                   String tipo, int signo, BigDecimal monto, String descripcion) {
        jdbc.update("""
                INSERT INTO movimiento
                       (cuenta_id, pago_id, fecha, tipo, signo, monto, descripcion, origen)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TRANSACCION')
                """, cuentaId, pagoId, java.sql.Date.valueOf(fecha), tipo, signo, monto, descripcion);
    }

    public List<Movimiento> movimientosDe(int cuentaId, int limite) {
        return jdbc.query("""
                SELECT id, cuenta_id, pago_id, fecha, tipo, signo, monto, descripcion, origen
                  FROM movimiento
                 WHERE cuenta_id = ?
                 ORDER BY fecha DESC, id DESC
                 LIMIT ?
                """, MAPEADOR_MOVIMIENTO, cuentaId, limite);
    }

    // -----------------------------------------------------------------
    // Ciclo de vida de la cuenta
    // -----------------------------------------------------------------

    /**
     * Reserva el siguiente numero de cuenta.
     *
     * <p>Se calcula en la base y no en memoria: dos peticiones simultaneas que
     * leyeran el maximo por separado elegirian el mismo numero y una de las dos
     * fallaria al insertar. {@code FOR UPDATE} sobre la tabla completa seria
     * excesivo, de modo que se delega en una secuencia, que es justo la
     * herramienta que la base ofrece para esto.</p>
     */
    public int siguienteNumeroDeCuenta() {
        Integer siguiente = jdbc.queryForObject(
                "SELECT nextval('seq_numero_cuenta')", Integer.class);
        if (siguiente == null) {
            throw new IllegalStateException("La secuencia de numeros de cuenta no devolvio valor");
        }
        return siguiente;
    }

    public void insertar(int numeroCuenta, int clienteId, String titular, String tipoCuenta,
                         BigDecimal saldoInicial, String moneda) {
        jdbc.update("""
                INSERT INTO cuenta (numero_cuenta, cliente_id, titular, tipo_cuenta,
                                    saldo, moneda, estado)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVA')
                """, numeroCuenta, clienteId, titular, tipoCuenta, saldoInicial, moneda);
    }

    /**
     * Cambia el estado de la cuenta.
     *
     * <p>La condicion sobre el estado actual impide dos cosas a la vez: que se
     * reabra una cuenta cerrada -el cierre es definitivo- y que dos peticiones
     * simultaneas apliquen cambios contradictorios, porque la segunda no
     * encuentra la fila en el estado que esperaba y no modifica nada.</p>
     *
     * @return cuantas filas cambiaron: 1 si se aplico, 0 si el estado previo no
     *         era el esperado
     */
    public int cambiarEstado(int numeroCuenta, String estadoPrevio, String estadoNuevo) {
        if ("CERRADA".equals(estadoNuevo)) {
            return jdbc.update("""
                    UPDATE cuenta
                       SET estado = 'CERRADA', cerrada_en = now(), actualizada_en = now()
                     WHERE numero_cuenta = ? AND estado = ?
                    """, numeroCuenta, estadoPrevio);
        }
        return jdbc.update("""
                UPDATE cuenta
                   SET estado = ?, cerrada_en = NULL, actualizada_en = now()
                 WHERE numero_cuenta = ? AND estado = ?
                """, estadoNuevo, numeroCuenta, estadoPrevio);
    }

    public void registrarCambio(int cuentaId, String estadoPrevio, String estadoNuevo,
                                String motivo, String solicitadoPor) {
        jdbc.update("""
                INSERT INTO cuenta_bitacora
                       (cuenta_id, estado_previo, estado_nuevo, motivo, solicitado_por)
                VALUES (?, ?, ?, ?, ?)
                """, cuentaId, estadoPrevio, estadoNuevo, motivo, solicitadoPor);
    }

    public List<CambioDeEstado> bitacoraDe(int cuentaId, int limite) {
        return jdbc.query("""
                SELECT id, cuenta_id, estado_previo, estado_nuevo, motivo,
                       solicitado_por, ocurrido_en
                  FROM cuenta_bitacora
                 WHERE cuenta_id = ?
                 ORDER BY ocurrido_en DESC, id DESC
                 LIMIT ?
                """, (rs, fila) -> new CambioDeEstado(
                        rs.getLong("id"),
                        rs.getInt("cuenta_id"),
                        rs.getString("estado_previo"),
                        rs.getString("estado_nuevo"),
                        rs.getString("motivo"),
                        rs.getString("solicitado_por"),
                        rs.getTimestamp("ocurrido_en").toInstant()),
                cuentaId, limite);
    }

    public List<Cuenta> listarDeCliente(int clienteId, int limite) {
        return jdbc.query("""
                SELECT """ + COLUMNAS_CUENTA + """
                  FROM cuenta
                 WHERE cliente_id = ?
                 ORDER BY numero_cuenta
                 LIMIT ?
                """, MAPEADOR_CUENTA, clienteId, limite);
    }

    public boolean existe(int numeroCuenta) {
        Integer cuantas = jdbc.queryForObject(
                "SELECT count(*) FROM cuenta WHERE numero_cuenta = ?", Integer.class, numeroCuenta);
        return cuantas != null && cuantas > 0;
    }
}
