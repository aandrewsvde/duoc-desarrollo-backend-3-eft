package com.duoc.bancoxyz.cuentas.servicio;

import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudApertura;
import com.duoc.bancoxyz.cuentas.repositorio.CuentaDao;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe la cuenta nueva, su movimiento de apertura y su bitacora como una
 * sola unidad.
 *
 * <p>Vive en un componente aparte de {@link CicloDeVidaCuentas} por una razon
 * concreta: {@code @Transactional} actua mediante un proxy, y una llamada entre
 * metodos de la misma clase no pasa por el, sea el metodo publico o no. Si este
 * metodo estuviera junto al que lo invoca, la anotacion no haria nada y el
 * fallo seria silencioso: todo parece funcionar hasta que una de las tres
 * escrituras falla y las otras dos quedan hechas.</p>
 *
 * <p>La separacion tiene ademas un efecto deseado: la frontera transaccional
 * empieza DESPUES de la llamada de red que verifica al cliente, no antes. Una
 * llamada remota dentro de una transaccion mantiene abiertos la conexion y los
 * bloqueos durante todo lo que tarde el otro servicio.</p>
 */
@Component
public class RegistradorDeAperturas {

    private static final Logger log = LoggerFactory.getLogger(RegistradorDeAperturas.class);

    private final CuentaDao cuentas;
    private final String moneda;

    public RegistradorDeAperturas(CuentaDao cuentas, @Value("${banco.moneda:CLP}") String moneda) {
        this.cuentas = cuentas;
        this.moneda = moneda;
    }

    @Transactional
    public Cuenta registrar(SolicitudApertura solicitud, String titular, String solicitadoPor) {
        int numero = cuentas.siguienteNumeroDeCuenta();
        BigDecimal inicial = solicitud.montoInicial();

        cuentas.insertar(numero, solicitud.clienteId(), titular,
                solicitud.tipoCuenta(), inicial, moneda);

        // Si hay deposito de apertura se registra tambien como movimiento. Poner
        // el saldo sin el movimiento que lo explica dejaria una cuenta cuyo
        // dinero no cuadra con su historial desde el primer dia.
        if (inicial.signum() > 0) {
            cuentas.insertarMovimiento(numero, null, LocalDate.now(), "DEPOSITO", 1, inicial,
                    "Deposito de apertura");
        }

        cuentas.registrarCambio(numero, null, "ACTIVA",
                "Apertura de cuenta " + solicitud.tipoCuenta(), solicitadoPor);

        log.info("Cuenta {} abierta para el cliente {} ({}) con saldo inicial {} {}",
                numero, solicitud.clienteId(), solicitud.tipoCuenta(), inicial, moneda);

        return cuentas.buscar(numero).orElseThrow(() -> new IllegalStateException(
                "La cuenta " + numero + " no se pudo releer despues de abrirla"));
    }
}
