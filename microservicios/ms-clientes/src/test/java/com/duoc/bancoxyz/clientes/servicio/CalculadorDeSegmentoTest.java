package com.duoc.bancoxyz.clientes.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import com.duoc.bancoxyz.clientes.dominio.Cliente;
import com.duoc.bancoxyz.clientes.repositorio.ClienteDao;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifica el calculo del segmento comercial a partir de los eventos de saldo.
 *
 * <p>Corre contra una base real -H2 en modo PostgreSQL- y usa el MISMO
 * esquema que produccion. Buena parte de lo que se quiere comprobar vive en la
 * base: la clave primaria que da la idempotencia y el {@code ON CONFLICT} que
 * resuelve alta y actualizacion en una sola sentencia.</p>
 */
@SpringBootTest
class CalculadorDeSegmentoTest {

    @Autowired
    private CalculadorDeSegmento calculador;

    @Autowired
    private ClienteDao clientes;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String TOPICO = "banco.cuentas.saldo-actualizado";

    @BeforeEach
    void prepararDatos() {
        jdbc.update("DELETE FROM evento_procesado");
        jdbc.update("DELETE FROM saldo_por_cuenta");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("INSERT INTO cliente (id, nombre, edad) VALUES (1, 'Diana Prince', 35)");
        jdbc.update("INSERT INTO cliente (id, nombre, edad) VALUES (2, 'Bob Johnson', 40)");
    }

    private String nuevoEvento() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("Un saldo bajo el umbral deja al cliente en segmento estandar")
    void saldoBajoDejaEstandar() {
        calculador.asociarCuenta(101, 1, new BigDecimal("5000.00"));

        Cliente cliente = clientes.buscar(1).orElseThrow();
        assertThat(cliente.segmento()).isEqualTo("ESTANDAR");
        assertThat(cliente.saldoTotal()).isEqualByComparingTo("5000.00");
    }

    /**
     * El caso que justifica consolidar: ninguna cuenta por si sola alcanza el
     * umbral, pero la suma si. Mirar cuenta a cuenta daria la respuesta
     * equivocada.
     */
    @Test
    @DisplayName("Varias cuentas suman para alcanzar el segmento preferente")
    void variasCuentasSuman() {
        calculador.asociarCuenta(101, 1, new BigDecimal("12000.00"));
        calculador.asociarCuenta(102, 1, new BigDecimal("9000.00"));

        Cliente cliente = clientes.buscar(1).orElseThrow();
        assertThat(cliente.saldoTotal()).isEqualByComparingTo("21000.00");
        assertThat(cliente.segmento()).isEqualTo("PREFERENTE");
    }

    @Test
    @DisplayName("El umbral exacto ya cuenta como preferente")
    void umbralExactoEsPreferente() {
        calculador.asociarCuenta(101, 1, new BigDecimal("20000.00"));

        assertThat(clientes.buscar(1).orElseThrow().segmento()).isEqualTo("PREFERENTE");
    }

    @Test
    @DisplayName("Un saldo que baja devuelve al cliente a segmento estandar")
    void bajarDeSaldoDegradaElSegmento() {
        calculador.asociarCuenta(101, 1, new BigDecimal("25000.00"));
        assertThat(clientes.buscar(1).orElseThrow().segmento()).isEqualTo("PREFERENTE");

        // Mismo evento de cuenta con saldo menor: el ON CONFLICT reemplaza en
        // vez de acumular, que es justo lo que debe pasar.
        calculador.aplicarSaldo(nuevoEvento(), TOPICO, 101, new BigDecimal("1000.00"), 1);

        Cliente cliente = clientes.buscar(1).orElseThrow();
        assertThat(cliente.saldoTotal()).isEqualByComparingTo("1000.00");
        assertThat(cliente.segmento()).isEqualTo("ESTANDAR");
    }

    /**
     * La prueba central del consumo idempotente: Kafka entrega al menos una
     * vez, de modo que el mismo evento puede llegar dos veces. Sin la bitacora,
     * un reintento normal podria ascender de categoria a quien no corresponde.
     */
    @Test
    @DisplayName("El mismo evento aplicado dos veces solo cuenta una")
    void eventoRepetidoNoSeAplicaDosVeces() {
        String eventoId = nuevoEvento();

        boolean primera = calculador.aplicarSaldo(eventoId, TOPICO, 101, new BigDecimal("8000.00"), 1);
        boolean segunda = calculador.aplicarSaldo(eventoId, TOPICO, 101, new BigDecimal("8000.00"), 1);

        assertThat(primera).isTrue();
        assertThat(segunda).isFalse();
        assertThat(clientes.buscar(1).orElseThrow().saldoTotal()).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("Cada cliente consolida solo sus propias cuentas")
    void cadaClienteSumaLoSuyo() {
        calculador.asociarCuenta(101, 1, new BigDecimal("15000.00"));
        calculador.asociarCuenta(201, 2, new BigDecimal("3000.00"));

        assertThat(clientes.buscar(1).orElseThrow().saldoTotal()).isEqualByComparingTo("15000.00");
        assertThat(clientes.buscar(2).orElseThrow().saldoTotal()).isEqualByComparingTo("3000.00");
    }

    /**
     * Ocurre cuando llega el saldo de una cuenta que la migracion todavia no
     * asocio. No debe lanzar: si lanzara, el consumidor no confirmaria el
     * mensaje y Kafka lo reentregaria en un bucle del que no se sale solo.
     */
    @Test
    @DisplayName("Un saldo de cuenta sin titular conocido se omite sin fallar")
    void cuentaSinTitularSeOmite() {
        boolean aplicado = calculador.aplicarSaldo(
                nuevoEvento(), TOPICO, 999, new BigDecimal("1000.00"), null);

        assertThat(aplicado).isFalse();
        assertThat(clientes.buscar(1).orElseThrow().saldoTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("El titular se deduce de la cuenta cuando el evento no lo trae")
    void elTitularSeDeduceDeLaCuenta() {
        calculador.asociarCuenta(101, 1, new BigDecimal("1000.00"));

        // El evento SaldoActualizado no lleva clienteId: ms-cuentas no lo
        // conoce. Se resuelve por la asociacion que dejo la migracion.
        calculador.aplicarSaldo(nuevoEvento(), TOPICO, 101, new BigDecimal("30000.00"), null);

        Cliente cliente = clientes.buscar(1).orElseThrow();
        assertThat(cliente.saldoTotal()).isEqualByComparingTo("30000.00");
        assertThat(cliente.segmento()).isEqualTo("PREFERENTE");
    }
}
