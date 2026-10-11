package com.duoc.bancoxyz.notificaciones.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import com.duoc.bancoxyz.notificaciones.dominio.Notificacion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifica la bandeja en memoria.
 *
 * <p>Se construye a mano en vez de levantar el contexto: es una estructura de
 * datos sin dependencias, y arrancar Spring para probarla solo anadiria
 * segundos a cada ejecucion sin verificar nada mas.</p>
 */
class BandejaDeNotificacionesTest {

    private Notificacion aviso(int cuenta, String texto) {
        return new Notificacion(UUID.randomUUID().toString(), cuenta, "TX-" + cuenta,
                "CORREO", "INFORMATIVA", texto, Instant.now());
    }

    @Test
    @DisplayName("Las notificaciones se devuelven de la mas reciente a la mas antigua")
    void seDevuelvenEnOrdenInverso() {
        BandejaDeNotificaciones bandeja = new BandejaDeNotificaciones(10);
        bandeja.agregar(aviso(101, "primera"));
        bandeja.agregar(aviso(101, "segunda"));
        bandeja.agregar(aviso(101, "tercera"));

        List<Notificacion> ultimas = bandeja.ultimas(10);

        assertThat(ultimas).extracting(Notificacion::mensaje)
                .containsExactly("tercera", "segunda", "primera");
    }

    /**
     * Sin este tope, un proceso que lleva meses en marcha termina agotando la
     * memoria. La prueba fija el comportamiento: se descarta la mas antigua.
     */
    @Test
    @DisplayName("Al llegar al tope se descarta la mas antigua")
    void elTopeDescartaLasAntiguas() {
        BandejaDeNotificaciones bandeja = new BandejaDeNotificaciones(3);
        bandeja.agregar(aviso(101, "uno"));
        bandeja.agregar(aviso(101, "dos"));
        bandeja.agregar(aviso(101, "tres"));
        bandeja.agregar(aviso(101, "cuatro"));

        assertThat(bandeja.enMemoria()).isEqualTo(3);
        assertThat(bandeja.ultimas(10)).extracting(Notificacion::mensaje)
                .containsExactly("cuatro", "tres", "dos");
        // El contador total no se ve afectado por el descarte: sigue reflejando
        // cuantos eventos se recibieron de verdad.
        assertThat(bandeja.totalRecibidas()).isEqualTo(4);
    }

    @Test
    @DisplayName("El filtro por cuenta devuelve solo los avisos de esa cuenta")
    void filtraPorCuenta() {
        BandejaDeNotificaciones bandeja = new BandejaDeNotificaciones(10);
        bandeja.agregar(aviso(101, "de la 101"));
        bandeja.agregar(aviso(102, "de la 102"));
        bandeja.agregar(aviso(101, "otra de la 101"));

        List<Notificacion> deLa101 = bandeja.deCuenta(101, 10);

        assertThat(deLa101).hasSize(2);
        assertThat(deLa101).allMatch(notificacion -> notificacion.cuentaId() == 101);
    }

    @Test
    @DisplayName("El limite pedido nunca excede el tope de la bandeja")
    void elLimiteSeAcota() {
        BandejaDeNotificaciones bandeja = new BandejaDeNotificaciones(3);
        bandeja.agregar(aviso(101, "uno"));

        // Un cliente que pida un millon no debe poder forzar una lista enorme.
        assertThat(bandeja.ultimas(1_000_000)).hasSize(1);
    }

    @Test
    @DisplayName("Una bandeja recien creada no tiene nada")
    void bandejaVacia() {
        BandejaDeNotificaciones bandeja = new BandejaDeNotificaciones(10);

        assertThat(bandeja.ultimas(10)).isEmpty();
        assertThat(bandeja.totalRecibidas()).isZero();
        assertThat(bandeja.enMemoria()).isZero();
    }
}
