package com.duoc.bancoxyz.pagos.servicio;

import com.duoc.bancoxyz.eventos.SaldoActualizado;
import com.duoc.bancoxyz.eventos.PagoRechazado;
import com.duoc.bancoxyz.pagos.repositorio.EventoProcesadoDao;
import com.duoc.bancoxyz.pagos.repositorio.PagoDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra la saga con lo que informa ms-cuentas.
 *
 * <p>Es el paso que vuelve util a la coreografia: sin el, ms-pagos
 * registraria operaciones que quedarian pendientes para siempre y el canal no
 * sabria nunca si el dinero se movio.</p>
 */
@Service
public class ResolutorDeSaga {

    private static final Logger log = LoggerFactory.getLogger(ResolutorDeSaga.class);

    private final PagoDao pagos;
    private final EventoProcesadoDao eventos;

    public ResolutorDeSaga(PagoDao pagos, EventoProcesadoDao eventos) {
        this.pagos = pagos;
        this.eventos = eventos;
    }

    @Transactional
    public void confirmar(SaldoActualizado evento, String topico) {
        if (!eventos.reservar(evento.eventoId(), topico)) {
            log.debug("Confirmacion {} ya procesada; se ignora", evento.eventoId());
            return;
        }
        int filas = pagos.confirmar(
                evento.pagoId(), evento.saldoAnterior(), evento.saldoNuevo());

        if (filas == 1) {
            log.info("Pago {} CONFIRMADA: la cuenta {} paso de {} a {}",
                    evento.pagoId(), evento.cuentaId(),
                    evento.saldoAnterior(), evento.saldoNuevo());
        } else {
            // Puede ocurrir legitimamente si el evento llega repetido tras una
            // resolucion previa. Se registra en vez de fallar, porque reintentar
            // no cambiaria el desenlace.
            log.warn("La confirmacion de {} no cambio ninguna fila: el pago ya no estaba PENDIENTE",
                    evento.pagoId());
        }
    }

    /**
     * Compensa el pago.
     *
     * <p>La compensacion aqui es marcarla como revertida con su motivo, y no
     * deshacer un movimiento, porque el saldo nunca llego a cambiar: ms-cuentas
     * rechazo antes de tocarlo. Esa es la forma normal de compensar cuando el
     * paso que fallo es el que iba a modificar el estado.</p>
     */
    @Transactional
    public void compensar(PagoRechazado evento, String topico) {
        if (!eventos.reservar(evento.eventoId(), topico)) {
            log.debug("Rechazo {} ya procesado; se ignora", evento.eventoId());
            return;
        }
        int filas = pagos.compensar(evento.pagoId(), evento.motivo());

        if (filas == 1) {
            log.info("Pago {} COMPENSADA sobre la cuenta {}. Motivo: {}",
                    evento.pagoId(), evento.cuentaId(), evento.motivo());
        } else {
            log.warn("La compensacion de {} no cambio ninguna fila: el pago ya no estaba PENDIENTE",
                    evento.pagoId());
        }
    }
}
