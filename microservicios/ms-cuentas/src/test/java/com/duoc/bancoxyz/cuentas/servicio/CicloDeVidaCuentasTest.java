package com.duoc.bancoxyz.cuentas.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import com.duoc.bancoxyz.cuentas.cliente.ClienteDeClientes;
import com.duoc.bancoxyz.cuentas.cliente.ClienteNoExisteException;
import com.duoc.bancoxyz.cuentas.cliente.ClientesNoDisponibleException;
import com.duoc.bancoxyz.cuentas.cliente.ResumenCliente;
import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudApertura;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudCambioEstado;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifica la apertura, el bloqueo y el cierre de cuentas.
 *
 * <p>El cliente de ms-clientes se sustituye por un doble: aqui no se comprueba
 * la red -eso tiene su propia prueba- sino la decision que toma este servicio
 * con lo que ese otro le responde.</p>
 */
@SpringBootTest
class CicloDeVidaCuentasTest {

    @Autowired
    private CicloDeVidaCuentas cicloDeVida;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private ClienteDeClientes clientes;

    @BeforeEach
    void prepararDatos() {
        jdbc.update("DELETE FROM evento_procesado");
        jdbc.update("DELETE FROM cuenta_bitacora");
        jdbc.update("DELETE FROM movimiento");
        jdbc.update("DELETE FROM cuenta");
        given(clientes.verificar(anyInt())).willReturn(CompletableFuture.completedFuture(
                new ResumenCliente(1, "Diana Prince", "ACTIVO")));
    }

    @Test
    @DisplayName("Una cuenta nueva nace activa, con el titular que informo ms-clientes")
    void laCuentaNaceActiva() {
        Cuenta cuenta = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", new BigDecimal("50000.00")), "ejecutivo");

        assertThat(cuenta.estado()).isEqualTo("ACTIVA");
        assertThat(cuenta.clienteId()).isEqualTo(1);
        // El nombre NO viene de la solicitud: lo resuelve ms-clientes. Aceptarlo
        // del canal permitiria abrir una cuenta a nombre de cualquiera.
        assertThat(cuenta.titular()).isEqualTo("Diana Prince");
        assertThat(cuenta.saldo()).isEqualByComparingTo("50000.00");
        assertThat(cuenta.cerradaEn()).isNull();
        // La numeracion arranca en 1000 para no chocar con las cuentas 101-150
        // que trae la migracion del legado.
        assertThat(cuenta.numeroCuenta()).isGreaterThanOrEqualTo(1000);
    }

    /**
     * Poner el saldo sin el movimiento que lo explica dejaria una cuenta cuyo
     * dinero no cuadra con su historial desde el primer dia.
     */
    @Test
    @DisplayName("El deposito de apertura queda tambien como movimiento")
    void elDepositoDeAperturaSeRegistra() {
        Cuenta cuenta = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", new BigDecimal("50000.00")), "ejecutivo");

        Integer movimientos = jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE cuenta_id = ?", Integer.class,
                cuenta.numeroCuenta());
        assertThat(movimientos).isEqualTo(1);
    }

    @Test
    @DisplayName("Una apertura sin deposito no genera movimiento")
    void sinDepositoNoHayMovimiento() {
        Cuenta cuenta = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo");

        Integer movimientos = jdbc.queryForObject(
                "SELECT count(*) FROM movimiento WHERE cuenta_id = ?", Integer.class,
                cuenta.numeroCuenta());
        assertThat(movimientos).isZero();
        assertThat(cuenta.saldo()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("La apertura queda anotada en la bitacora con quien la pidio")
    void laAperturaSeAudita() {
        Cuenta cuenta = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo");

        assertThat(cicloDeVida.bitacora(cuenta.numeroCuenta(), 10))
                .singleElement()
                .satisfies(cambio -> {
                    assertThat(cambio.estadoPrevio()).isNull();
                    assertThat(cambio.estadoNuevo()).isEqualTo("ACTIVA");
                    // Quien pidio la operacion sale del token, no del cuerpo de
                    // la peticion: si el solicitante se declarara a si mismo, la
                    // bitacora dejaria de servir para auditar.
                    assertThat(cambio.solicitadoPor()).isEqualTo("ejecutivo");
                });
    }

    @Test
    @DisplayName("Un cliente suspendido no puede abrir cuentas")
    void clienteSuspendidoNoAbre() {
        given(clientes.verificar(anyInt())).willReturn(CompletableFuture.completedFuture(
                new ResumenCliente(1, "Diana Prince", "SUSPENDIDO")));

        assertThatThrownBy(() -> cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo"))
                .isInstanceOf(CambioDeEstadoInvalidoException.class)
                .hasMessageContaining("SUSPENDIDO");
    }

    /**
     * El respaldo de este servicio falla CERRADO, al reves que el de ms-pagos.
     * Abrir la cuenta igualmente crearia un registro a nombre de alguien que
     * quiza no existe, y deshacerlo despues es trabajo manual sobre datos
     * financieros.
     */
    @Test
    @DisplayName("Si no se puede verificar al cliente, la cuenta NO se abre")
    void sinVerificacionNoSeAbre() {
        given(clientes.verificar(anyInt())).willReturn(CompletableFuture.failedFuture(
                new ClientesNoDisponibleException("ms-clientes no responde")));

        assertThatThrownBy(() -> cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo"))
                .hasRootCauseInstanceOf(ClientesNoDisponibleException.class);

        Integer cuentas = jdbc.queryForObject("SELECT count(*) FROM cuenta", Integer.class);
        assertThat(cuentas).isZero();
    }

    @Test
    @DisplayName("Un cliente inexistente propaga el error de negocio")
    void clienteInexistente() {
        given(clientes.verificar(anyInt())).willReturn(CompletableFuture.failedFuture(
                new ClienteNoExisteException(99)));

        assertThatThrownBy(() -> cicloDeVida.abrir(
                new SolicitudApertura(99, "AHORRO", BigDecimal.ZERO), "ejecutivo"))
                .hasRootCauseInstanceOf(ClienteNoExisteException.class);
    }

    @Test
    @DisplayName("Una cuenta se bloquea y se vuelve a activar")
    void bloqueoYDesbloqueo() {
        int numero = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo").numeroCuenta();

        Cuenta bloqueada = cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("BLOQUEADA", "Sospecha de fraude"), "seguridad");
        assertThat(bloqueada.estado()).isEqualTo("BLOQUEADA");
        assertThat(bloqueada.admiteMovimientos()).isFalse();

        Cuenta reactivada = cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("ACTIVA", "Verificacion superada"), "seguridad");
        assertThat(reactivada.estado()).isEqualTo("ACTIVA");
        assertThat(cicloDeVida.bitacora(numero, 10)).hasSize(3);
    }

    /**
     * Es el caso que mas duele en un banco: dinero atrapado en una cuenta a la
     * que ya nadie puede acceder.
     */
    @Test
    @DisplayName("No se cierra una cuenta con saldo distinto de cero")
    void noSeCierraConSaldo() {
        int numero = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", new BigDecimal("1000.00")), "ejecutivo")
                .numeroCuenta();

        assertThatThrownBy(() -> cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("CERRADA", "Peticion del titular"), "ejecutivo"))
                .isInstanceOf(CambioDeEstadoInvalidoException.class)
                .hasMessageContaining("saldo");
    }

    @Test
    @DisplayName("Una cuenta en cero se cierra y queda con fecha de cierre")
    void cierreEnCero() {
        int numero = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo").numeroCuenta();

        Cuenta cerrada = cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("CERRADA", "Peticion del titular"), "ejecutivo");

        assertThat(cerrada.estado()).isEqualTo("CERRADA");
        assertThat(cerrada.cerradaEn()).isNotNull();
    }

    @Test
    @DisplayName("El cierre es definitivo: una cuenta cerrada no se reabre")
    void elCierreEsDefinitivo() {
        int numero = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo").numeroCuenta();
        cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("CERRADA", "Peticion del titular"), "ejecutivo");

        assertThatThrownBy(() -> cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("ACTIVA", "Se arrepintio"), "ejecutivo"))
                .isInstanceOf(CambioDeEstadoInvalidoException.class)
                .hasMessageContaining("definitivo");
    }

    @Test
    @DisplayName("Cambiar al mismo estado que ya tiene es un conflicto")
    void mismoEstadoEsConflicto() {
        int numero = cicloDeVida.abrir(
                new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo").numeroCuenta();

        assertThatThrownBy(() -> cicloDeVida.cambiarEstado(numero,
                new SolicitudCambioEstado("ACTIVA", "Por si acaso"), "ejecutivo"))
                .isInstanceOf(CambioDeEstadoInvalidoException.class)
                .hasMessageContaining("ya esta");
    }

    @Test
    @DisplayName("Las cuentas se listan por cliente")
    void listadoPorCliente() {
        cicloDeVida.abrir(new SolicitudApertura(1, "AHORRO", BigDecimal.ZERO), "ejecutivo");
        cicloDeVida.abrir(new SolicitudApertura(1, "PRESTAMO", BigDecimal.ZERO), "ejecutivo");

        assertThat(cicloDeVida.deCliente(1, 10)).hasSize(2);
        assertThat(cicloDeVida.deCliente(2, 10)).isEmpty();
    }
}
