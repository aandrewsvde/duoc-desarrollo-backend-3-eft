package com.duoc.bancoxyz.batch.migracion;

import java.math.BigDecimal;
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
 * Carga los titulares y sus cuentas en ms-clientes y ms-cuentas.
 *
 * <p>Es el paso que convierte el resultado del calculo de intereses -que vive
 * en la tabla de trabajo del batch- en los datos operativos de dos
 * microservicios. Se ejecuta al final del job y solo si la calidad de los datos
 * fue suficiente: cargar en las bases operativas un resultado que el propio
 * proceso considera dudoso seria peor que no cargar nada.</p>
 *
 * <p>El sistema legado no tiene identificadores de cliente: tiene nombres
 * repetidos en muchas filas. Aqui se deduce la identidad agrupando por nombre,
 * que es el unico criterio disponible, y se deja constancia del supuesto. En
 * una migracion real este es el punto donde se cruzaria contra el RUT.</p>
 */
public class MigracionClientesYCuentasTasklet implements Tasklet {

    private static final Logger log =
            LoggerFactory.getLogger(MigracionClientesYCuentasTasklet.class);

    private final JdbcTemplate origen;
    private final JdbcTemplate destinoClientes;
    private final JdbcTemplate destinoCuentas;
    private final BigDecimal umbralPreferente;

    public MigracionClientesYCuentasTasklet(JdbcTemplate origen,
                                            JdbcTemplate destinoClientes,
                                            JdbcTemplate destinoCuentas,
                                            BigDecimal umbralPreferente) {
        this.origen = origen;
        this.destinoClientes = destinoClientes;
        this.destinoCuentas = destinoCuentas;
        this.umbralPreferente = umbralPreferente;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) {
        // UNA fila por cuenta, elegida explicitamente.
        //
        // El archivo legado trae unas veinte filas por cuenta con datos
        // contradictorios entre si, y el calculo de intereses las procesa
        // todas: de 1000 filas quedan ~400 validas para solo 50 cuentas. Dar
        // por hecho que hay una sola por cuenta hace que la insercion falle
        // entera por clave duplicada.
        //
        // Se conserva la ULTIMA procesada de cada cuenta (mayor id). El criterio
        // es arbitrario pero DETERMINISTA, que es lo que importa: dos
        // migraciones del mismo archivo deben producir exactamente el mismo
        // resultado, o la evidencia deja de ser reproducible.
        List<Map<String, Object>> cuentas = origen.queryForList("""
                SELECT DISTINCT ON (cuenta_id)
                       cuenta_id, nombre, tipo_cuenta, edad, saldo_final
                  FROM cuenta_interes
                 ORDER BY cuenta_id, id DESC
                """);

        if (cuentas.isEmpty()) {
            log.warn("No hay cuentas que migrar: la tabla cuenta_interes esta vacia");
            return RepeatStatus.FINISHED;
        }

        // La migracion se repite desde cero en cada ejecucion. Es deliberado:
        // un job de migracion debe poder relanzarse y dejar el mismo resultado,
        // y acumular sobre lo anterior dejaria cuentas duplicadas al reintentar.
        //
        // CONSECUENCIA IMPORTANTE: esto vacia tambien la tabla de movimientos,
        // que carga el job de estados anuales. Por eso los tres jobs forman una
        // unidad y se lanzan juntos con /api/batch/migracion-completa; ejecutar
        // este solo deja el sistema con las cuentas puestas y sin su historial.
        // La alternativa -borrar solo lo que este job escribio- obligaria a
        // marcar cada fila con la ejecucion que la creo, que es complejidad
        // real a cambio de permitir algo que no se necesita.
        destinoCuentas.update("DELETE FROM cuenta_bitacora");
        destinoCuentas.update("DELETE FROM movimiento");
        destinoCuentas.update("DELETE FROM cuenta");
        destinoClientes.update("DELETE FROM saldo_por_cuenta");
        destinoClientes.update("DELETE FROM cliente");

        // Identidad deducida por nombre, ordenada para que el identificador que
        // recibe cada titular sea el mismo en cada ejecucion. Sin ese orden,
        // dos migraciones del mismo archivo asignarian numeros distintos y la
        // evidencia dejaria de ser reproducible.
        List<String> titulares = cuentas.stream()
                .map(fila -> (String) fila.get("nombre"))
                .distinct()
                .sorted()
                .toList();

        int siguienteCliente = 1;
        java.util.Map<String, Integer> idPorNombre = new java.util.LinkedHashMap<>();
        for (String nombre : titulares) {
            idPorNombre.put(nombre, siguienteCliente++);
        }

        List<Object[]> filasCliente = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> titular : idPorNombre.entrySet()) {
            Integer edad = cuentas.stream()
                    .filter(f -> titular.getKey().equals(f.get("nombre")))
                    .map(f -> (Number) f.get("edad"))
                    .filter(java.util.Objects::nonNull)
                    .map(Number::intValue)
                    .findFirst()
                    .orElse(null);
            filasCliente.add(new Object[]{titular.getValue(), titular.getKey(), edad});
        }
        destinoClientes.batchUpdate(
                "INSERT INTO cliente (id, nombre, edad) VALUES (?, ?, ?)", filasCliente);

        List<Object[]> filasCuenta = new java.util.ArrayList<>();
        List<Object[]> filasSaldo = new java.util.ArrayList<>();
        List<Object[]> filasBitacora = new java.util.ArrayList<>();
        for (Map<String, Object> fila : cuentas) {
            int numero = ((Number) fila.get("cuenta_id")).intValue();
            String nombre = (String) fila.get("nombre");
            int clienteId = idPorNombre.get(nombre);
            String tipo = ((String) fila.get("tipo_cuenta")).toUpperCase();
            BigDecimal saldo = (BigDecimal) fila.get("saldo_final");

            filasCuenta.add(new Object[]{numero, clienteId, nombre, tipo, saldo, "CLP"});
            filasSaldo.add(new Object[]{numero, clienteId, saldo});
            filasBitacora.add(new Object[]{numero, "ACTIVA",
                    "Alta por migracion del sistema legado", "batch-financiero"});
        }

        destinoCuentas.batchUpdate("""
                INSERT INTO cuenta (numero_cuenta, cliente_id, titular, tipo_cuenta,
                                    saldo, moneda, estado)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVA')
                """, filasCuenta);

        destinoCuentas.batchUpdate("""
                INSERT INTO cuenta_bitacora (cuenta_id, estado_previo, estado_nuevo,
                                             motivo, solicitado_por)
                VALUES (?, NULL, ?, ?, ?)
                """, filasBitacora);

        destinoClientes.batchUpdate("""
                INSERT INTO saldo_por_cuenta (cuenta_id, cliente_id, saldo) VALUES (?, ?, ?)
                """, filasSaldo);

        // El segmento se calcula aqui con la misma regla que aplica ms-clientes
        // en caliente. Dejarlo sin calcular obligaria a esperar al primer
        // movimiento de cada cliente para que su categoria fuera correcta.
        int preferentes = destinoClientes.update("""
                UPDATE cliente c
                   SET saldo_total = t.total,
                       segmento = CASE WHEN t.total >= ? THEN 'PREFERENTE' ELSE 'ESTANDAR' END
                  FROM (SELECT cliente_id, sum(saldo) AS total
                          FROM saldo_por_cuenta GROUP BY cliente_id) t
                 WHERE c.id = t.cliente_id
                """, umbralPreferente);

        Integer cuantosPreferentes = destinoClientes.queryForObject(
                "SELECT count(*) FROM cliente WHERE segmento = 'PREFERENTE'", Integer.class);

        log.info("Migracion completada: {} clientes y {} cuentas. {} consolidados, {} preferentes.",
                filasCliente.size(), filasCuenta.size(), preferentes, cuantosPreferentes);

        contribucion.incrementWriteCount(filasCliente.size() + filasCuenta.size());
        return RepeatStatus.FINISHED;
    }
}
