package com.duoc.bancoxyz.bff.movil.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.duoc.bancoxyz.canal.cliente.ClienteDelNucleo;
import com.duoc.bancoxyz.canal.dto.ClienteDelCore;
import com.duoc.bancoxyz.canal.dto.CuentaDelCore;
import com.duoc.bancoxyz.canal.dto.MovimientoDelCore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Verifica la adaptacion que hace el canal movil.
 *
 * <p>Es lo que distingue a un BFF de un simple proxy: el nucleo devuelve un
 * dato y el canal decide como mostrarlo. Esta prueba fija esas decisiones -el
 * nombre abreviado, el importe redondeado, el tipo de una letra- para que no
 * se pierdan sin que nadie se entere.</p>
 *
 * <p>No levanta el contexto de Spring: el controlador no necesita nada mas que
 * su dependencia, y construirlo a mano hace la prueba instantanea.</p>
 */
class ResumenMovilControladorTest {

    private ClienteDelNucleo nucleo;
    private ResumenMovilControlador controlador;

    @BeforeEach
    void preparar() {
        nucleo = mock(ClienteDelNucleo.class);
        controlador = new ResumenMovilControlador(nucleo, 3, "8092", "10.0.0.5");

        given(nucleo.cliente(anyInt())).willReturn(new ClienteDelCore(
                1, "Diana Prince", 35, "PREFERENTE", new BigDecimal("21500.75"), "ACTIVO"));
        given(nucleo.cuentasDe(anyInt())).willReturn(List.of(new CuentaDelCore(
                101, 1, "Diana Prince", "AHORRO", new BigDecimal("8000.49"), "CLP", "ACTIVA")));
        given(nucleo.movimientos(anyInt(), ArgumentMatchers.anyInt())).willReturn(List.of(
                new MovimientoDelCore(1, 101, LocalDate.of(2024, 5, 9), "DEPOSITO", 1,
                        new BigDecimal("2500.00"), "Ingreso de fin de ano", "LEGACY"),
                new MovimientoDelCore(2, 101, LocalDate.of(2024, 4, 30), "RETIRO", -1,
                        new BigDecimal("1000.00"), "Retiro parcial", "LEGACY")));
    }

    @Test
    @DisplayName("El nombre del titular se abrevia a inicial y apellido")
    void elNombreSeAbrevia() {
        Map<String, Object> respuesta = controlador.resumen(1);

        // En una pantalla de telefono el nombre completo se corta igual; es
        // mejor decidir donde se corta que dejarlo al ancho disponible.
        assertThat(respuesta.get("n")).isEqualTo("D. Prince");
    }

    @Test
    @DisplayName("Los importes se redondean a entero")
    void losImportesSeRedondean() {
        Map<String, Object> respuesta = controlador.resumen(1);

        // 21500.75 -> 21501. Nadie decide nada mirando los centavos en un
        // telefono, y cada decimal son bytes que viajan por red movil.
        assertThat(respuesta.get("tot")).hasToString("21501");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cuentas = (List<Map<String, Object>>) respuesta.get("ctas");
        assertThat(cuentas.get(0).get("s")).hasToString("8000");
    }

    @Test
    @DisplayName("El segmento viaja como una sola letra")
    void elSegmentoSeAbrevia() {
        assertThat(controlador.resumen(1).get("seg")).isEqualTo("P");
    }

    @Test
    @DisplayName("Los tipos viajan como una letra, con su tabla publicada una vez")
    void losTiposSeAbrevian() {
        Map<String, Object> respuesta = controlador.resumen(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cuentas = (List<Map<String, Object>>) respuesta.get("ctas");
        assertThat(cuentas.get(0).get("t")).isEqualTo("A");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movimientos =
                (List<Map<String, Object>>) cuentas.get(0).get("m");
        assertThat(movimientos.get(0).get("t")).isEqualTo("D");
        assertThat(movimientos.get(1).get("t")).isEqualTo("R");

        // La tabla va una vez por respuesta y no una por movimiento: con varias
        // cuentas, repetir la palabra completa costaria mas que publicarla.
        @SuppressWarnings("unchecked")
        Map<String, String> tipos = (Map<String, String>) respuesta.get("tipos");
        assertThat(tipos).containsEntry("D", "DEPOSITO").containsEntry("R", "RETIRO");
    }

    @Test
    @DisplayName("El signo contable se aplica al importe del movimiento")
    void elSignoSeAplica() {
        Map<String, Object> respuesta = controlador.resumen(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cuentas = (List<Map<String, Object>>) respuesta.get("ctas");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movimientos =
                (List<Map<String, Object>>) cuentas.get(0).get("m");

        assertThat(movimientos.get(0).get("n")).hasToString("2500");
        // El retiro llega con signo -1 y debe mostrarse en negativo: que el
        // canal lo resuelva evita que cada aplicacion movil lo interprete a su
        // manera.
        assertThat(movimientos.get(1).get("n")).hasToString("-1000");
    }

    @Test
    @DisplayName("Un nombre de una sola palabra se deja tal cual")
    void nombreDeUnaPalabra() {
        given(nucleo.cliente(anyInt())).willReturn(new ClienteDelCore(
                1, "Prince", 35, "ESTANDAR", BigDecimal.ZERO, "ACTIVO"));

        assertThat(controlador.resumen(1).get("n")).isEqualTo("Prince");
    }

    @Test
    @DisplayName("La respuesta identifica el canal y la instancia que respondio")
    void identificaCanalEInstancia() {
        Map<String, Object> respuesta = controlador.resumen(1);

        assertThat(respuesta.get("canal")).isEqualTo("MOVIL");
        // Con varias replicas detras del balanceador es la unica forma de ver
        // desde fuera que el reparto es real.
        assertThat(respuesta.get("instancia")).isEqualTo("bff-movil:10.0.0.5:8092");
    }
}
