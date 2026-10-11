package com.duoc.bancoxyz.notificaciones.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.PagoRechazado;
import com.duoc.bancoxyz.notificaciones.dominio.Notificacion;
import com.duoc.bancoxyz.notificaciones.servicio.BandejaDeNotificaciones;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Verifica que cada desenlace de la saga produzca el aviso que corresponde. */
class ConsumidorDeResultadosTest {

    private BandejaDeNotificaciones bandeja;
    private ConsumidorDeResultados consumidor;
    private Acknowledgment confirmacion;

    @BeforeEach
    void preparar() {
        bandeja = new BandejaDeNotificaciones(10);
        consumidor = new ConsumidorDeResultados(bandeja);
        confirmacion = mock(Acknowledgment.class);
    }

    @Test
    @DisplayName("Un saldo actualizado genera un aviso informativo con ambos saldos")
    void elSaldoActualizadoGeneraAvisoInformativo() {
        consumidor.saldoActualizado(new SaldoActualizado(UUID.randomUUID().toString(),
                "TX-001", 101, new BigDecimal("8000.00"), new BigDecimal("6000.00"),
                "CLP", Instant.now()), confirmacion);

        Notificacion aviso = bandeja.ultimas(1).get(0);
        assertThat(aviso.nivel()).isEqualTo("INFORMATIVA");
        assertThat(aviso.canal()).isEqualTo("CORREO");
        assertThat(aviso.cuentaId()).isEqualTo(101);
        assertThat(aviso.mensaje()).contains("8000.00").contains("6000.00").contains("TX-001");
    }

    /**
     * El rechazo va como ALERTA y por un canal inmediato: un aviso de saldo se
     * puede leer mas tarde, pero una operacion que no se completo exige que el
     * titular se entere ahora, probablemente frente a un cajero.
     */
    @Test
    @DisplayName("Un rechazo genera una alerta por un canal inmediato")
    void elRechazoGeneraAlerta() {
        consumidor.pagoRechazado(new PagoRechazado(UUID.randomUUID().toString(),
                "TX-002", 101, "Saldo insuficiente", Instant.now()), confirmacion);

        Notificacion aviso = bandeja.ultimas(1).get(0);
        assertThat(aviso.nivel()).isEqualTo("ALERTA");
        assertThat(aviso.canal()).isEqualTo("SMS");
        assertThat(aviso.mensaje()).contains("Saldo insuficiente").contains("TX-002");
    }

    /**
     * El acuse debe darse siempre tras procesar. Si no se diera, Kafka volveria
     * a entregar el mismo evento indefinidamente.
     */
    @Test
    @DisplayName("Ambos consumos confirman la lectura del mensaje")
    void siempreSeConfirmaLaLectura() {
        consumidor.saldoActualizado(new SaldoActualizado(UUID.randomUUID().toString(),
                "TX-003", 101, BigDecimal.ONE, BigDecimal.TEN, "CLP", Instant.now()), confirmacion);
        consumidor.pagoRechazado(new PagoRechazado(UUID.randomUUID().toString(),
                "TX-004", 101, "motivo", Instant.now()), confirmacion);

        verify(confirmacion, org.mockito.Mockito.times(2)).acknowledge();
        assertThat(bandeja.totalRecibidas()).isEqualTo(2);
    }
}
