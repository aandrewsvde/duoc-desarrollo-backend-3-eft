package com.duoc.bancoxyz.clientes.servicio;

import com.duoc.bancoxyz.clientes.repositorio.ClienteDao;
import com.duoc.bancoxyz.clientes.repositorio.EventoProcesadoDao;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantiene al dia el saldo consolidado y el segmento comercial del cliente.
 *
 * <p>Reacciona a los cambios de saldo que publica ms-cuentas. Es el ejemplo mas
 * claro de por que conviene la mensajeria: ms-cuentas no sabe que existe este
 * calculo, no lo invoca y no se entera si falla. Si manana el banco cambia la
 * regla del segmento, se cambia aqui y nadie mas se toca.</p>
 *
 * <p>La alternativa sincrona seria preguntar a ms-cuentas por todas las cuentas
 * del titular cada vez que alguien consulta su perfil. Eso convertiria la
 * consulta mas frecuente del sistema en una llamada de red, y haria que una
 * caida de ms-cuentas impidiera incluso leer un nombre.</p>
 */
@Service
public class CalculadorDeSegmento {

    private static final Logger log = LoggerFactory.getLogger(CalculadorDeSegmento.class);

    private final ClienteDao clientes;
    private final EventoProcesadoDao eventos;
    private final BigDecimal umbralPreferente;

    public CalculadorDeSegmento(ClienteDao clientes, EventoProcesadoDao eventos,
                                @Value("${banco.segmento.umbral-preferente:20000}") BigDecimal umbral) {
        this.clientes = clientes;
        this.eventos = eventos;
        this.umbralPreferente = umbral;
    }

    /**
     * Aplica un cambio de saldo y recalcula el segmento.
     *
     * <p>Idempotente: si el evento ya se proceso, se descarta sin tocar nada.
     * Sin esa proteccion una reentrega de Kafka volveria a sumar el mismo saldo
     * y podria ascender de categoria a un cliente que no corresponde.</p>
     *
     * @return {@code true} si el evento se aplico; {@code false} si era repetido
     *         o la cuenta todavia no esta asociada a ningun cliente
     */
    @Transactional
    public boolean aplicarSaldo(String eventoId, String topico, int cuentaId,
                                BigDecimal saldoNuevo, Integer clienteConocido) {
        if (!eventos.reservar(eventoId, topico)) {
            log.debug("Evento {} ya procesado; no se recalcula el segmento", eventoId);
            return false;
        }

        Integer clienteId = clienteConocido != null
                ? clienteConocido
                : clientes.clienteDeCuenta(cuentaId).orElse(null);

        if (clienteId == null) {
            // Ocurre si llega el saldo de una cuenta que la migracion todavia no
            // asocio. No es un error: se ignora y el proximo evento de esa
            // cuenta, ya asociada, la pondra al dia.
            log.warn("No se conoce el titular de la cuenta {}; se omite el recalculo", cuentaId);
            return false;
        }

        BigDecimal total = clientes.registrarSaldoDeCuenta(cuentaId, clienteId, saldoNuevo);
        String segmento = total.compareTo(umbralPreferente) >= 0 ? "PREFERENTE" : "ESTANDAR";
        clientes.actualizarConsolidado(clienteId, total, segmento);

        log.info("Cliente {}: saldo consolidado {} -> segmento {}", clienteId, total, segmento);
        return true;
    }

    /** Asocia una cuenta a un cliente sin cambiar el saldo; la usa la migracion. */
    @Transactional
    public void asociarCuenta(int cuentaId, int clienteId, BigDecimal saldo) {
        BigDecimal total = clientes.registrarSaldoDeCuenta(cuentaId, clienteId, saldo);
        String segmento = total.compareTo(umbralPreferente) >= 0 ? "PREFERENTE" : "ESTANDAR";
        clientes.actualizarConsolidado(clienteId, total, segmento);
    }
}
