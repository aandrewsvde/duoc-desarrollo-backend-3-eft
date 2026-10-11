package com.duoc.bancoxyz.clientes.repositorio;

import com.duoc.bancoxyz.clientes.dominio.Cliente;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Acceso a las tablas propias de ms-clientes. */
@Repository
public class ClienteDao {

    private final JdbcTemplate jdbc;

    public ClienteDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNAS = """
            id, nombre, edad, segmento, saldo_total, estado, creado_en, actualizado_en
            """;

    private static final RowMapper<Cliente> MAPEADOR = (ResultSet rs, int fila) -> {
        // getInt devuelve 0 para NULL, de modo que hay que preguntar aparte:
        // una edad 0 y una edad ausente no son lo mismo.
        //
        // wasNull() se refiere SIEMPRE a la ultima columna leida, de modo que
        // hay que consultarlo aqui y no mas abajo: intercalar la lectura de
        // otra columna lo haria responder por esa otra, y el resultado seria
        // una edad 0 en vez de null sin ningun error visible.
        int edadLeida = rs.getInt("edad");
        Integer edad = rs.wasNull() ? null : edadLeida;

        return new Cliente(
                rs.getInt("id"),
                rs.getString("nombre"),
                edad,
                rs.getString("segmento"),
                rs.getBigDecimal("saldo_total"),
                rs.getString("estado"),
                rs.getTimestamp("creado_en").toInstant(),
                rs.getTimestamp("actualizado_en").toInstant());
    };

    public int siguienteId() {
        Integer siguiente = jdbc.queryForObject("SELECT nextval('seq_cliente')", Integer.class);
        if (siguiente == null) {
            throw new IllegalStateException("La secuencia de clientes no devolvio valor");
        }
        return siguiente;
    }

    public void insertar(int id, String nombre, Integer edad) {
        jdbc.update("INSERT INTO cliente (id, nombre, edad) VALUES (?, ?, ?)", id, nombre, edad);
    }

    public Optional<Cliente> buscar(int id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNAS + " FROM cliente WHERE id = ?", MAPEADOR, id));
        } catch (EmptyResultDataAccessException sinFilas) {
            return Optional.empty();
        }
    }

    public List<Cliente> listar(int limite) {
        return jdbc.query("SELECT " + COLUMNAS + """
                 FROM cliente
                ORDER BY id
                LIMIT ?
                """, MAPEADOR, limite);
    }

    public List<Cliente> buscarPorNombre(String fragmento, int limite) {
        return jdbc.query("SELECT " + COLUMNAS + """
                 FROM cliente
                WHERE lower(nombre) LIKE lower(?)
                ORDER BY nombre, id
                LIMIT ?
                """, MAPEADOR, "%" + fragmento + "%", limite);
    }

    public int actualizarPerfil(int id, String nombre, Integer edad) {
        return jdbc.update("""
                UPDATE cliente
                   SET nombre = ?, edad = ?, actualizado_en = now()
                 WHERE id = ? AND estado <> 'BAJA'
                """, nombre, edad, id);
    }

    public int cambiarEstado(int id, String estado) {
        return jdbc.update(
                "UPDATE cliente SET estado = ?, actualizado_en = now() WHERE id = ?", estado, id);
    }

    public boolean existe(int id) {
        Integer cuantos = jdbc.queryForObject(
                "SELECT count(*) FROM cliente WHERE id = ?", Integer.class, id);
        return cuantos != null && cuantos > 0;
    }

    // -----------------------------------------------------------------
    // Saldo consolidado, alimentado por eventos
    // -----------------------------------------------------------------

    /**
     * Registra el saldo de una cuenta y devuelve el total del cliente.
     *
     * <p>{@code ON CONFLICT} resuelve en una sola sentencia el alta y la
     * actualizacion. Hacerlo en dos -consultar y luego insertar o actualizar-
     * dejaria una ventana en la que dos eventos de la misma cuenta podrian
     * insertar ambos y uno fallaria.</p>
     */
    public BigDecimal registrarSaldoDeCuenta(int cuentaId, int clienteId, BigDecimal saldo) {
        // MERGE y no "INSERT ... ON CONFLICT": la forma estandar la entienden
        // tanto PostgreSQL 15+ como el H2 de las pruebas, de modo que estas
        // ejercitan la MISMA sentencia que corre en produccion. Con la sintaxis
        // propia de PostgreSQL habria que mantener dos versiones, y la que se
        // prueba no seria la que se despliega.
        jdbc.update("""
                MERGE INTO saldo_por_cuenta d
                USING (VALUES (?, ?, ?)) AS s (cuenta_id, cliente_id, saldo)
                   ON d.cuenta_id = s.cuenta_id
                 WHEN MATCHED THEN
                      UPDATE SET saldo = s.saldo,
                                 cliente_id = s.cliente_id,
                                 actualizado_en = now()
                 WHEN NOT MATCHED THEN
                      INSERT (cuenta_id, cliente_id, saldo)
                      VALUES (s.cuenta_id, s.cliente_id, s.saldo)
                """, cuentaId, clienteId, saldo);

        BigDecimal total = jdbc.queryForObject("""
                SELECT coalesce(sum(saldo), 0) FROM saldo_por_cuenta WHERE cliente_id = ?
                """, BigDecimal.class, clienteId);
        return total == null ? BigDecimal.ZERO : total;
    }

    public void actualizarConsolidado(int clienteId, BigDecimal saldoTotal, String segmento) {
        jdbc.update("""
                UPDATE cliente
                   SET saldo_total = ?, segmento = ?, actualizado_en = now()
                 WHERE id = ?
                """, saldoTotal, segmento, clienteId);
    }

    public Optional<Integer> clienteDeCuenta(int cuentaId) {
        List<Integer> encontrados = jdbc.queryForList(
                "SELECT cliente_id FROM saldo_por_cuenta WHERE cuenta_id = ?",
                Integer.class, cuentaId);
        return encontrados.isEmpty() ? Optional.empty() : Optional.of(encontrados.get(0));
    }
}
