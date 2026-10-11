package com.duoc.bancoxyz.clientes.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duoc.bancoxyz.clientes.dominio.Cliente;
import com.duoc.bancoxyz.clientes.dominio.ClienteNoEncontradoException;
import com.duoc.bancoxyz.clientes.dominio.SolicitudCliente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifica el alta y el mantenimiento del perfil de los clientes. */
@SpringBootTest
class ClienteServicioTest {

    @Autowired
    private ClienteServicio servicio;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM evento_procesado");
        jdbc.update("DELETE FROM saldo_por_cuenta");
        jdbc.update("DELETE FROM cliente");
    }

    @Test
    @DisplayName("Un cliente nuevo nace activo, estandar y con saldo cero")
    void elClienteNaceEstandar() {
        Cliente cliente = servicio.registrar(new SolicitudCliente("Diana Prince", 35));

        assertThat(cliente.id()).isPositive();
        assertThat(cliente.nombre()).isEqualTo("Diana Prince");
        assertThat(cliente.estado()).isEqualTo("ACTIVO");
        // El segmento NO se acepta del exterior: nace estandar y solo los
        // eventos de saldo pueden cambiarlo.
        assertThat(cliente.segmento()).isEqualTo("ESTANDAR");
        assertThat(cliente.saldoTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("El nombre se guarda sin espacios sobrantes")
    void elNombreSeNormaliza() {
        Cliente cliente = servicio.registrar(new SolicitudCliente("   Steve Rogers  ", 45));

        assertThat(cliente.nombre()).isEqualTo("Steve Rogers");
    }

    /**
     * El archivo legado trae la edad vacia en 171 de 1000 filas. Perder al
     * cliente por eso seria peor que admitir el hueco, de modo que la columna
     * acepta nulo y el servicio tambien.
     */
    @Test
    @DisplayName("Un cliente sin edad declarada se acepta")
    void laEdadEsOpcional() {
        Cliente cliente = servicio.registrar(new SolicitudCliente("Bruce Wayne", null));

        assertThat(cliente.edad()).isNull();
        assertThat(cliente.estaActivo()).isTrue();
    }

    @Test
    @DisplayName("Cada cliente recibe un identificador distinto")
    void losIdentificadoresNoSeRepiten() {
        int primero = servicio.registrar(new SolicitudCliente("Uno", 30)).id();
        int segundo = servicio.registrar(new SolicitudCliente("Dos", 30)).id();

        assertThat(primero).isNotEqualTo(segundo);
    }

    @Test
    @DisplayName("Actualizar el perfil cambia nombre y edad")
    void seActualizaElPerfil() {
        int id = servicio.registrar(new SolicitudCliente("Diana Prince", 35)).id();

        Cliente actualizado = servicio.actualizarPerfil(id, new SolicitudCliente("Diana P. Themyscira", 36));

        assertThat(actualizado.nombre()).isEqualTo("Diana P. Themyscira");
        assertThat(actualizado.edad()).isEqualTo(36);
    }

    /**
     * La baja es logica y no un borrado. Las cuentas y los pagos que ese
     * cliente genero siguen existiendo en los otros servicios y deben poder
     * explicarse; borrar la fila dejaria huerfanos registros financieros que
     * por ley hay que conservar.
     */
    @Test
    @DisplayName("La baja conserva la fila y solo cambia el estado")
    void laBajaEsLogica() {
        int id = servicio.registrar(new SolicitudCliente("Diana Prince", 35)).id();

        Cliente dado = servicio.darDeBaja(id);

        assertThat(dado.estado()).isEqualTo("BAJA");
        assertThat(dado.estaActivo()).isFalse();
        assertThat(servicio.obtener(id)).isNotNull();
    }

    @Test
    @DisplayName("No se da de baja dos veces al mismo cliente")
    void noSeDaDeBajaDosVeces() {
        int id = servicio.registrar(new SolicitudCliente("Diana Prince", 35)).id();
        servicio.darDeBaja(id);

        assertThatThrownBy(() -> servicio.darDeBaja(id))
                .isInstanceOf(EstadoClienteInvalidoException.class)
                .hasMessageContaining("ya estaba de baja");
    }

    @Test
    @DisplayName("El perfil de un cliente de baja ya no se modifica")
    void elPerfilDeUnaBajaNoSeToca() {
        int id = servicio.registrar(new SolicitudCliente("Diana Prince", 35)).id();
        servicio.darDeBaja(id);

        assertThatThrownBy(() -> servicio.actualizarPerfil(id, new SolicitudCliente("Otro", 40)))
                .isInstanceOf(EstadoClienteInvalidoException.class);
    }

    @Test
    @DisplayName("Consultar un cliente inexistente lanza el error de dominio")
    void clienteInexistente() {
        assertThatThrownBy(() -> servicio.obtener(999999))
                .isInstanceOf(ClienteNoEncontradoException.class);
    }

    @Test
    @DisplayName("La busqueda por nombre no distingue mayusculas")
    void busquedaPorNombre() {
        servicio.registrar(new SolicitudCliente("Diana Prince", 35));
        servicio.registrar(new SolicitudCliente("Bob Johnson", 40));

        assertThat(servicio.buscarPorNombre("diana", 10)).hasSize(1);
        assertThat(servicio.buscarPorNombre("PRINCE", 10)).hasSize(1);
        assertThat(servicio.buscarPorNombre("zzz", 10)).isEmpty();
    }
}
