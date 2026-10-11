package com.duoc.bancoxyz.pagos.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.Topicos;
import com.duoc.bancoxyz.eventos.PagoRechazado;
import com.duoc.bancoxyz.pagos.dominio.EstadoPago;
import com.duoc.bancoxyz.pagos.dominio.Pago;
import com.duoc.bancoxyz.pagos.repositorio.PagoDao;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifica el cierre de la saga: que un pago PENDIENTE termine
 * confirmada o compensada, y que no pueda cambiar dos veces.
 *
 * <p>Es la parte del sistema donde un error pasa inadvertido con mas
 * facilidad. Un pago que se queda PENDIENTE para siempre no lanza
 * ninguna excepcion ni aparece en ningun registro de error: simplemente nadie
 * vuelve a mirarla.</p>
 */
@SpringBootTest
class ResolutorDeSagaTest {

    @Autowired
    private ResolutorDeSaga resolutor;

    @Autowired
    private PagoDao pagos;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String ID = "TX-PRUEBA-0001";

    @BeforeEach
    void dejarUnaPagoPendiente() {
        jdbc.update("DELETE FROM evento_procesado");
        jdbc.update("DELETE FROM pago");
        pagos.insertarPendiente(new Pago(
                ID, 101, "RETIRO", new BigDecimal("2000.00"), "CLP", "WEB",
                EstadoPago.PENDIENTE, null, null, null,
                "REF-" + UUID.randomUUID(), Instant.now(), null));
    }

    private SaldoActualizado confirmacion() {
        return new SaldoActualizado(UUID.randomUUID().toString(), ID, 101,
                new BigDecimal("8000.00"), new BigDecimal("6000.00"), "CLP", Instant.now());
    }

    private PagoRechazado rechazo(String motivo) {
        return new PagoRechazado(UUID.randomUUID().toString(), ID, 101, motivo, Instant.now());
    }

    @Test
    @DisplayName("La confirmacion deja el pago CONFIRMADA con los saldos informados")
    void laConfirmacionCierraLaSaga() {
        resolutor.confirmar(confirmacion(), Topicos.SALDO_ACTUALIZADO);

        Pago resultante = pagos.buscar(ID).orElseThrow();
        assertThat(resultante.estado()).isEqualTo(EstadoPago.CONFIRMADA);
        assertThat(resultante.saldoAnterior()).isEqualByComparingTo("8000.00");
        assertThat(resultante.saldoNuevo()).isEqualByComparingTo("6000.00");
        assertThat(resultante.resueltoEn()).isNotNull();
        assertThat(resultante.motivo()).isNull();
    }

    @Test
    @DisplayName("El rechazo deja el pago COMPENSADA con su motivo")
    void elRechazoCompensaLaPago() {
        resolutor.compensar(rechazo("Saldo insuficiente: disponible 100.00"), Topicos.PAGOS_RECHAZADOS);

        Pago resultante = pagos.buscar(ID).orElseThrow();
        assertThat(resultante.estado()).isEqualTo(EstadoPago.COMPENSADA);
        assertThat(resultante.motivo()).contains("Saldo insuficiente");
        assertThat(resultante.resueltoEn()).isNotNull();
        // La compensacion no inventa saldos: ms-cuentas nunca llego a moverlos.
        assertThat(resultante.saldoNuevo()).isNull();
    }

    @Test
    @DisplayName("El mismo evento de confirmacion repetido no se vuelve a aplicar")
    void laConfirmacionRepetidaSeIgnora() {
        SaldoActualizado mismo = confirmacion();

        resolutor.confirmar(mismo, Topicos.SALDO_ACTUALIZADO);
        resolutor.confirmar(mismo, Topicos.SALDO_ACTUALIZADO);

        Integer anotados = jdbc.queryForObject(
                "SELECT count(*) FROM evento_procesado WHERE evento_id = ?",
                Integer.class, mismo.eventoId());
        assertThat(anotados).isEqualTo(1);
        assertThat(pagos.buscar(ID).orElseThrow().estado())
                .isEqualTo(EstadoPago.CONFIRMADA);
    }

    /**
     * El caso que justifica la condicion {@code estado = 'PENDIENTE'} en el
     * UPDATE. Dos eventos con identificadores distintos sortean la bitacora de
     * idempotencia, de modo que es la propia sentencia la que debe impedir que
     * un pago ya resuelta vuelva a cambiar de estado.
     */
    @Test
    @DisplayName("Un rechazo tardio no reabre un pago ya confirmada")
    void elRechazoTardioNoReabreLoConfirmado() {
        resolutor.confirmar(confirmacion(), Topicos.SALDO_ACTUALIZADO);

        resolutor.compensar(rechazo("llego tarde"), Topicos.PAGOS_RECHAZADOS);

        Pago resultante = pagos.buscar(ID).orElseThrow();
        assertThat(resultante.estado()).isEqualTo(EstadoPago.CONFIRMADA);
        assertThat(resultante.motivo()).isNull();
    }

    @Test
    @DisplayName("Una confirmacion tardia no revierte una compensacion")
    void laConfirmacionTardiaNoRevierteLaCompensacion() {
        resolutor.compensar(rechazo("Saldo insuficiente"), Topicos.PAGOS_RECHAZADOS);

        resolutor.confirmar(confirmacion(), Topicos.SALDO_ACTUALIZADO);

        Pago resultante = pagos.buscar(ID).orElseThrow();
        assertThat(resultante.estado()).isEqualTo(EstadoPago.COMPENSADA);
        assertThat(resultante.saldoNuevo()).isNull();
    }

    @Test
    @DisplayName("Un evento para un pago inexistente no rompe el consumo")
    void unEventoHuerfanoNoLanza() {
        SaldoActualizado huerfano = new SaldoActualizado(UUID.randomUUID().toString(),
                "TX-QUE-NO-EXISTE", 101, BigDecimal.ONE, BigDecimal.TEN, "CLP", Instant.now());

        // No debe lanzar: si lanzara, el consumidor no confirmaria el mensaje y
        // Kafka lo reentregaria en un bucle del que no se sale solo.
        resolutor.confirmar(huerfano, Topicos.SALDO_ACTUALIZADO);

        assertThat(pagos.buscar("TX-QUE-NO-EXISTE")).isEmpty();
    }
}
