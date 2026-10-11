package com.duoc.bancoxyz.cuentas.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import com.duoc.bancoxyz.cuentas.dominio.ResultadoAplicacion;
import com.duoc.bancoxyz.cuentas.repositorio.EventoProcesadoDao;
import com.duoc.bancoxyz.eventos.PagoRegistrado;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifica las reglas con las que ms-cuentas acepta o rechaza un pago.
 *
 * <p>Se ejecuta contra una base real -H2 en modo PostgreSQL- y no con dobles de
 * prueba, porque buena parte de lo que se quiere comprobar vive en la base:
 * la clave primaria que da la idempotencia y la atomicidad de el pago.
 * Simular el DAO verificaria que el codigo llama a los metodos esperados, que
 * no es lo mismo que verificar que el dinero cuadra.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AplicadorDePagosTest {

    @Autowired
    private AplicadorDePagos aplicador;

    @Autowired
    private EventoProcesadoDao eventos;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepararDatos() {
        jdbc.update("DELETE FROM evento_procesado");
        jdbc.update("DELETE FROM cuenta_bitacora");
        jdbc.update("DELETE FROM movimiento");
        jdbc.update("DELETE FROM cuenta");
        jdbc.update("""
                INSERT INTO cuenta (numero_cuenta, cliente_id, titular, tipo_cuenta,
                                    saldo, moneda, estado)
                VALUES (101, 1, 'Diana Prince', 'AHORRO', 8000.00, 'CLP', 'ACTIVA')
                """);
        jdbc.update("""
                INSERT INTO cuenta (numero_cuenta, cliente_id, titular, tipo_cuenta,
                                    saldo, moneda, estado)
                VALUES (102, 2, 'Bob Johnson', 'AHORRO', 500.00, 'CLP', 'BLOQUEADA')
                """);
    }

    private PagoRegistrado evento(int cuenta, String tipo, String monto) {
        return new PagoRegistrado(UUID.randomUUID().toString(), "PG-" + cuenta + "-" + tipo,
                cuenta, tipo, new BigDecimal(monto), "CLP", "WEB", Instant.now());
    }

    @Test
    @DisplayName("Un deposito suma al saldo y deja el movimiento registrado")
    void depositoSumaAlSaldo() {
        ResultadoAplicacion resultado = aplicador.aplicar(evento(101, "DEPOSITO", "1500.00"));

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Aplicada.class);
        ResultadoAplicacion.Aplicada aplicada = (ResultadoAplicacion.Aplicada) resultado;
        assertThat(aplicada.saldoAnterior()).isEqualByComparingTo("8000.00");
        assertThat(aplicada.saldoNuevo()).isEqualByComparingTo("9500.00");

        BigDecimal enBase = jdbc.queryForObject(
                "SELECT saldo FROM cuenta WHERE numero_cuenta = 101", BigDecimal.class);
        assertThat(enBase).isEqualByComparingTo("9500.00");

        Integer movimientos = jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE cuenta_id = 101 AND origen = 'TRANSACCION'",
                Integer.class);
        assertThat(movimientos).isEqualTo(1);
    }

    @Test
    @DisplayName("Un retiro resta del saldo")
    void retiroRestaDelSaldo() {
        ResultadoAplicacion resultado = aplicador.aplicar(evento(101, "RETIRO", "2000.00"));

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Aplicada.class);
        assertThat(((ResultadoAplicacion.Aplicada) resultado).saldoNuevo())
                .isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("Un retiro mayor que el saldo se rechaza y no modifica la cuenta")
    void retiroSinSaldoSeRechaza() {
        ResultadoAplicacion resultado = aplicador.aplicar(evento(101, "RETIRO", "9000.00"));

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Rechazada.class);
        assertThat(((ResultadoAplicacion.Rechazada) resultado).motivo())
                .contains("Saldo insuficiente");

        // Lo decisivo: el rechazo no deja rastro contable.
        BigDecimal saldo = jdbc.queryForObject(
                "SELECT saldo FROM cuenta WHERE numero_cuenta = 101", BigDecimal.class);
        assertThat(saldo).isEqualByComparingTo("8000.00");
        Integer movimientos = jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE cuenta_id = 101", Integer.class);
        assertThat(movimientos).isZero();
    }

    @Test
    @DisplayName("Un retiro que deja el saldo exactamente en cero si se acepta")
    void retiroQueDejaElSaldoEnCeroSeAcepta() {
        ResultadoAplicacion resultado = aplicador.aplicar(evento(101, "RETIRO", "8000.00"));

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Aplicada.class);
        assertThat(((ResultadoAplicacion.Aplicada) resultado).saldoNuevo())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Una cuenta que no existe se rechaza con su motivo")
    void cuentaInexistenteSeRechaza() {
        ResultadoAplicacion resultado = aplicador.aplicar(evento(999, "DEPOSITO", "100.00"));

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Rechazada.class);
        assertThat(((ResultadoAplicacion.Rechazada) resultado).motivo()).contains("no existe");
    }

    @Test
    @DisplayName("Una cuenta bloqueada no acepta movimientos")
    void cuentaBloqueadaSeRechaza() {
        ResultadoAplicacion resultado = aplicador.aplicar(evento(102, "DEPOSITO", "100.00"));

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Rechazada.class);
        assertThat(((ResultadoAplicacion.Rechazada) resultado).motivo()).contains("BLOQUEADA");
    }

    @Test
    @DisplayName("Una moneda distinta de la de la cuenta se rechaza")
    void monedaDistintaSeRechaza() {
        PagoRegistrado enDolares = new PagoRegistrado(UUID.randomUUID().toString(),
                "PG-USD", 101, "DEPOSITO", new BigDecimal("100.00"), "USD", "WEB", Instant.now());

        ResultadoAplicacion resultado = aplicador.aplicar(enDolares);

        assertThat(resultado).isInstanceOf(ResultadoAplicacion.Rechazada.class);
        assertThat(((ResultadoAplicacion.Rechazada) resultado).motivo()).contains("USD");
    }

    /**
     * La prueba central del consumo idempotente: Kafka entrega al menos una vez,
     * de modo que el mismo evento puede llegar dos veces. Sin la bitacora, el
     * dinero se descontaria dos veces por un reintento que es normal y esperable.
     */
    @Test
    @DisplayName("El mismo evento aplicado dos veces solo mueve el saldo una vez")
    void eventoRepetidoNoDuplicaElCargo() {
        PagoRegistrado mismo = evento(101, "RETIRO", "1000.00");

        ResultadoAplicacion primera = aplicador.aplicar(mismo);
        ResultadoAplicacion segunda = aplicador.aplicar(mismo);

        assertThat(primera).isInstanceOf(ResultadoAplicacion.Aplicada.class);
        assertThat(segunda).isInstanceOf(ResultadoAplicacion.Duplicada.class);

        BigDecimal saldo = jdbc.queryForObject(
                "SELECT saldo FROM cuenta WHERE numero_cuenta = 101", BigDecimal.class);
        assertThat(saldo).isEqualByComparingTo("7000.00");

        Integer movimientos = jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE cuenta_id = 101", Integer.class);
        assertThat(movimientos).isEqualTo(1);
    }

    @Test
    @DisplayName("Dos eventos distintos sobre la misma cuenta se acumulan")
    void eventosDistintosSeAcumulan() {
        aplicador.aplicar(evento(101, "DEPOSITO", "1000.00"));
        aplicador.aplicar(evento(101, "RETIRO", "500.00"));

        BigDecimal saldo = jdbc.queryForObject(
                "SELECT saldo FROM cuenta WHERE numero_cuenta = 101", BigDecimal.class);
        assertThat(saldo).isEqualByComparingTo("8500.00");
        assertThat(eventos.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("Un rechazo queda anotado en la bitacora para no reevaluarse")
    void elRechazoTambienSeAnota() {
        PagoRegistrado sinSaldo = evento(101, "RETIRO", "99999.00");

        aplicador.aplicar(sinSaldo);
        ResultadoAplicacion segunda = aplicador.aplicar(sinSaldo);

        // Importa que el reintento no vuelva a emitir un evento de rechazo: el
        // titular recibiria dos avisos por una sola operacion fallida.
        assertThat(segunda).isInstanceOf(ResultadoAplicacion.Duplicada.class);
        String resultado = jdbc.queryForObject(
                "SELECT resultado FROM evento_procesado WHERE evento_id = ?",
                String.class, sinSaldo.eventoId());
        assertThat(resultado).isEqualTo("RECHAZADA");
    }
}
