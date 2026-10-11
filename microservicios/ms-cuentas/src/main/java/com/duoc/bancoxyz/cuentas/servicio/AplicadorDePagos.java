package com.duoc.bancoxyz.cuentas.servicio;

import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.ResultadoAplicacion;
import com.duoc.bancoxyz.cuentas.repositorio.CuentaDao;
import com.duoc.bancoxyz.cuentas.repositorio.EventoProcesadoDao;
import com.duoc.bancoxyz.eventos.PagoRegistrado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica sobre el saldo un pago recibida por evento.
 *
 * <p>Es el paso del medio de la saga: ms-pagos registro la operacion
 * como PENDIENTE y publico el hecho; aqui se decide si se confirma o se
 * compensa, y esa decision vuelve como un evento nuevo.</p>
 */
@Service
public class AplicadorDePagos {

    private static final Logger log = LoggerFactory.getLogger(AplicadorDePagos.class);

    /** Tipos que suman al saldo; cualquier otro resta. */
    private static final java.util.Set<String> ABONOS = java.util.Set.of("DEPOSITO", "ABONO");

    private final CuentaDao cuentas;
    private final EventoProcesadoDao eventos;

    public AplicadorDePagos(CuentaDao cuentas, EventoProcesadoDao eventos) {
        this.cuentas = cuentas;
        this.eventos = eventos;
    }

    /**
     * Aplica el pago de forma atomica e idempotente.
     *
     * <p>La reserva del evento, el movimiento y la actualizacion del saldo
     * ocurren en la misma pago de base de datos. Eso importa: si el
     * proceso muriera despues de descontar el saldo pero antes de registrar el
     * evento como procesado, al reiniciar el consumidor volveria a recibirlo y
     * lo descontaria otra vez. Al estar todo en la misma pago, o quedan
     * las tres cosas o no queda ninguna.</p>
     */
    @Transactional
    public ResultadoAplicacion aplicar(PagoRegistrado evento) {
        if (!eventos.reservar(evento.eventoId(), "banco.pagos.registrados")) {
            log.info("Evento {} ya estaba aplicado; se ignora sin volver a tocar el saldo",
                    evento.eventoId());
            return new ResultadoAplicacion.Duplicada(evento.eventoId());
        }

        Optional<Cuenta> encontrada = cuentas.bloquearParaActualizar(evento.cuentaId());
        if (encontrada.isEmpty()) {
            eventos.marcarResultado(evento.eventoId(), "RECHAZADA");
            return new ResultadoAplicacion.Rechazada(
                    "La cuenta " + evento.cuentaId() + " no existe");
        }

        Cuenta cuenta = encontrada.get();
        if (!"ACTIVA".equals(cuenta.estado())) {
            eventos.marcarResultado(evento.eventoId(), "RECHAZADA");
            return new ResultadoAplicacion.Rechazada(
                    "La cuenta " + cuenta.numeroCuenta() + " esta " + cuenta.estado());
        }
        if (!cuenta.moneda().equals(evento.moneda())) {
            eventos.marcarResultado(evento.eventoId(), "RECHAZADA");
            return new ResultadoAplicacion.Rechazada(
                    "Moneda " + evento.moneda() + " distinta de la cuenta (" + cuenta.moneda() + ")");
        }

        int signo = ABONOS.contains(evento.tipo()) ? 1 : -1;
        BigDecimal saldoAnterior = cuenta.saldo();
        BigDecimal saldoNuevo = saldoAnterior.add(evento.monto().multiply(BigDecimal.valueOf(signo)));

        // El sobregiro se trata como resultado de negocio y no como excepcion:
        // reintentarlo no cambiaria nada, de modo que debe volver al origen como
        // un rechazo definitivo para que compense su pago.
        if (signo < 0 && saldoNuevo.signum() < 0) {
            eventos.marcarResultado(evento.eventoId(), "RECHAZADA");
            log.warn("Pago {} rechazada por saldo insuficiente: cuenta {} tiene {} y se pidieron {}",
                    evento.pagoId(), cuenta.numeroCuenta(), saldoAnterior, evento.monto());
            return new ResultadoAplicacion.Rechazada("Saldo insuficiente: disponible "
                    + saldoAnterior.toPlainString() + ", solicitado " + evento.monto().toPlainString());
        }

        cuentas.insertarMovimiento(cuenta.numeroCuenta(), evento.pagoId(), LocalDate.now(),
                evento.tipo(), signo, evento.monto(),
                "Pago " + evento.pagoId() + " por canal digital");
        cuentas.actualizarSaldo(cuenta.numeroCuenta(), saldoNuevo);
        eventos.marcarResultado(evento.eventoId(), "APLICADA");

        log.info("Pago {} aplicada sobre la cuenta {}: {} -> {}",
                evento.pagoId(), cuenta.numeroCuenta(), saldoAnterior, saldoNuevo);
        return new ResultadoAplicacion.Aplicada(saldoAnterior, saldoNuevo, cuenta.moneda());
    }
}
