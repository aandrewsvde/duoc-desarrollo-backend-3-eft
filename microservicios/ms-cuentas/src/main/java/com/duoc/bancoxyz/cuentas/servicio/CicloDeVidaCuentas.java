package com.duoc.bancoxyz.cuentas.servicio;

import com.duoc.bancoxyz.cuentas.cliente.ClienteDeClientes;
import com.duoc.bancoxyz.cuentas.cliente.ResumenCliente;
import com.duoc.bancoxyz.cuentas.dominio.CambioDeEstado;
import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudApertura;
import com.duoc.bancoxyz.cuentas.dominio.SolicitudCambioEstado;
import com.duoc.bancoxyz.cuentas.repositorio.CuentaDao;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apertura, cierre y mantenimiento de cuentas.
 *
 * <p>Es la parte del servicio que cambia la EXISTENCIA de una cuenta, no su
 * saldo. Esa separacion importa: el saldo se mueve miles de veces al dia por
 * eventos, mientras que abrir o cerrar ocurre pocas veces, siempre a peticion
 * de alguien identificado, y siempre debe quedar registrado para auditoria.</p>
 */
@Service
public class CicloDeVidaCuentas {

    private static final Logger log = LoggerFactory.getLogger(CicloDeVidaCuentas.class);

    private final CuentaDao cuentas;
    private final ClienteDeClientes clientes;
    private final RegistradorDeAperturas registrador;

    public CicloDeVidaCuentas(CuentaDao cuentas, ClienteDeClientes clientes,
                              RegistradorDeAperturas registrador) {
        this.cuentas = cuentas;
        this.clientes = clientes;
        this.registrador = registrador;
    }

    /**
     * Abre una cuenta nueva.
     *
     * <p>El nombre del titular NO se recibe del canal: se resuelve aqui contra
     * ms-clientes. Aceptarlo tal cual permitiria abrir una cuenta a nombre de
     * cualquiera con solo escribir otro texto en la peticion.</p>
     *
     * <p>La verificacion ocurre FUERA de la transaccion de base de datos, y no
     * es un descuido: una llamada de red dentro de una transaccion mantiene
     * abierta la conexion y los bloqueos durante todo lo que tarde el otro
     * servicio, de modo que una dependencia lenta acaba agotando el pool.</p>
     *
     * <p>Si hay deposito de apertura se registra como movimiento ademas de
     * quedar en el saldo. Poner el saldo sin el movimiento que lo explica
     * dejaria una cuenta cuyo dinero no cuadra con su historial desde el primer
     * dia.</p>
     */
    public Cuenta abrir(SolicitudApertura solicitud, String solicitadoPor) {
        ResumenCliente cliente = clientes.verificar(solicitud.clienteId()).join();

        if (!cliente.estaActivo()) {
            throw new CambioDeEstadoInvalidoException(
                    "El cliente " + cliente.id() + " esta " + cliente.estado()
                    + " y no puede abrir cuentas nuevas");
        }

        return registrador.registrar(solicitud, cliente.nombre(), solicitadoPor);
    }

    /**
     * Bloquea, desbloquea o cierra una cuenta.
     *
     * <p>Dos reglas que la base no puede expresar por si sola y que por eso
     * viven aqui: una cuenta CERRADA no vuelve a abrirse, y no se cierra una
     * cuenta con saldo distinto de cero. La segunda evita el caso que mas duele
     * en un banco, que es dinero que queda atrapado en una cuenta a la que ya
     * nadie puede acceder.</p>
     */
    @Transactional
    public Cuenta cambiarEstado(int numeroCuenta, SolicitudCambioEstado solicitud,
                                String solicitadoPor) {
        Cuenta cuenta = cuentas.buscar(numeroCuenta)
                .orElseThrow(() -> new CuentaNoEncontradaException(numeroCuenta));

        String previo = cuenta.estado();
        String nuevo = solicitud.estado();

        if (previo.equals(nuevo)) {
            throw new CambioDeEstadoInvalidoException(
                    "La cuenta " + numeroCuenta + " ya esta " + previo);
        }
        if ("CERRADA".equals(previo)) {
            throw new CambioDeEstadoInvalidoException(
                    "La cuenta " + numeroCuenta + " esta cerrada y el cierre es definitivo");
        }
        if ("CERRADA".equals(nuevo) && cuenta.saldo().signum() != 0) {
            throw new CambioDeEstadoInvalidoException(
                    "No se puede cerrar la cuenta " + numeroCuenta + " con saldo "
                    + cuenta.saldo().toPlainString() + " " + cuenta.moneda()
                    + ": primero hay que dejarla en cero");
        }

        int filas = cuentas.cambiarEstado(numeroCuenta, previo, nuevo);
        if (filas != 1) {
            // Alguien cambio el estado entre la lectura y la escritura. No se
            // reintenta en silencio: el solicitante debe volver a decidir sobre
            // el estado real, que ya no es el que vio.
            throw new CambioDeEstadoInvalidoException(
                    "El estado de la cuenta " + numeroCuenta + " cambio mientras se procesaba "
                    + "la solicitud; vuelva a consultarla");
        }

        cuentas.registrarCambio(numeroCuenta, previo, nuevo, solicitud.motivo(), solicitadoPor);
        log.info("Cuenta {}: {} -> {} por {} ({})",
                numeroCuenta, previo, nuevo, solicitadoPor, solicitud.motivo());

        return cuentas.buscar(numeroCuenta).orElseThrow();
    }

    public List<CambioDeEstado> bitacora(int numeroCuenta, int limite) {
        if (!cuentas.existe(numeroCuenta)) {
            throw new CuentaNoEncontradaException(numeroCuenta);
        }
        return cuentas.bitacoraDe(numeroCuenta, Math.clamp(limite, 1, 100));
    }

    public List<Cuenta> deCliente(int clienteId, int limite) {
        return cuentas.listarDeCliente(clienteId, Math.clamp(limite, 1, 100));
    }
}
