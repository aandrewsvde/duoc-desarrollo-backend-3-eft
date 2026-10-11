package com.duoc.bancoxyz.pagos.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import com.duoc.bancoxyz.pagos.cliente.ClienteCuentas;
import com.duoc.bancoxyz.pagos.cliente.ResumenCuenta;
import com.duoc.bancoxyz.pagos.cliente.ValidacionPrevia;
import com.duoc.bancoxyz.pagos.dominio.EstadoPago;
import com.duoc.bancoxyz.pagos.dominio.SolicitudPago;
import com.duoc.bancoxyz.pagos.mensajeria.PublicadorDeEventos;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifica el alta de pagos.
 *
 * <p>El cliente de ms-cuentas y el publicador se sustituyen por dobles: aqui
 * no se esta comprobando ni la red ni Kafka -eso tiene sus propias pruebas-
 * sino la decision que toma este servicio con lo que esos dos le responden.</p>
 */
@SpringBootTest
class PagoServicioTest {

    @Autowired
    private PagoServicio servicio;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private ClienteCuentas cuentas;

    @MockBean
    private PublicadorDeEventos publicador;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM evento_procesado");
        jdbc.update("DELETE FROM pago");
        given(cuentas.validarCuenta(anyInt())).willReturn(CompletableFuture.completedFuture(
                ValidacionPrevia.confirmada(new ResumenCuenta(
                        101, "Diana Prince", new BigDecimal("8000.00"), "CLP", "ACTIVA"))));
    }

    private SolicitudPago solicitud(String referencia) {
        return new SolicitudPago(101, "RETIRO", new BigDecimal("2000.00"), referencia, "WEB");
    }

    @Test
    @DisplayName("Un pago nuevo nace PENDIENTE y publica su evento")
    void elPagoNaceEnEstadoPendiente() {
        PagoServicio.ResultadoCreacion resultado = servicio.crear(solicitud("REF-001"));

        assertThat(resultado.yaExistia()).isFalse();
        assertThat(resultado.pago().estado()).isEqualTo(EstadoPago.PENDIENTE);
        assertThat(resultado.pago().id()).startsWith("PG-");
        // El saldo no se toca aqui: eso le corresponde a ms-cuentas.
        assertThat(resultado.pago().saldoNuevo()).isNull();

        Mockito.verify(publicador).publicarYEsperar(
                Mockito.eq("banco.pagos.registrados"),
                Mockito.eq(resultado.pago().id()),
                Mockito.any());
    }

    /**
     * Idempotencia del canal: un cajero que reenvia la orden tras un corte de
     * red, o un usuario que pulsa dos veces, no deben generar dos cargos.
     */
    @Test
    @DisplayName("La misma referencia no crea una segunda pago ni publica otro evento")
    void laReferenciaRepetidaDevuelveLaOriginal() {
        PagoServicio.ResultadoCreacion primera = servicio.crear(solicitud("REF-REPETIDA"));
        PagoServicio.ResultadoCreacion segunda = servicio.crear(solicitud("REF-REPETIDA"));

        assertThat(segunda.yaExistia()).isTrue();
        assertThat(segunda.pago().id()).isEqualTo(primera.pago().id());

        Integer cuantas = jdbc.queryForObject(
                "SELECT count(*) FROM pago WHERE referencia = 'REF-REPETIDA'", Integer.class);
        assertThat(cuantas).isEqualTo(1);

        Mockito.verify(publicador, Mockito.times(1))
                .publicarYEsperar(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    /**
     * Con ms-cuentas degradado el pago se acepta igual. Rechazarla
     * convertiria la caida de una dependencia en una caida de este servicio,
     * cuando la decision definitiva la toma ms-cuentas al aplicar el evento.
     */
    @Test
    @DisplayName("Si no se pudo verificar la cuenta, el pago se acepta igualmente")
    void sinVerificacionLaPagoSigueSuCurso() {
        given(cuentas.validarCuenta(anyInt())).willReturn(CompletableFuture.completedFuture(
                ValidacionPrevia.noVerificada("No se pudo verificar la cuenta 101")));

        PagoServicio.ResultadoCreacion resultado = servicio.crear(solicitud("REF-DEGRADADA"));

        assertThat(resultado.pago().estado()).isEqualTo(EstadoPago.PENDIENTE);
        assertThat(resultado.observacion()).contains("No se pudo verificar");
        Mockito.verify(publicador).publicarYEsperar(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    @DisplayName("Cada pago recibe un identificador distinto")
    void cadaPagoTieneIdentificadorPropio() {
        String primera = servicio.crear(solicitud("REF-" + UUID.randomUUID())).pago().id();
        String segunda = servicio.crear(solicitud("REF-" + UUID.randomUUID())).pago().id();

        assertThat(primera).isNotEqualTo(segunda);
    }

    @Test
    @DisplayName("Consultar un pago inexistente lanza el error de dominio")
    void pagoInexistente() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> servicio.obtener("TX-NO-EXISTE"))
                .isInstanceOf(PagoNoEncontradoException.class);
    }
}
