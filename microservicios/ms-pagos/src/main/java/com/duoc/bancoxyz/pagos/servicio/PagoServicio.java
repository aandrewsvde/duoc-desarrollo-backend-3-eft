package com.duoc.bancoxyz.pagos.servicio;

import com.duoc.bancoxyz.pagos.cliente.ClienteCuentas;
import com.duoc.bancoxyz.pagos.cliente.ValidacionPrevia;
import com.duoc.bancoxyz.pagos.dominio.EstadoPago;
import com.duoc.bancoxyz.pagos.dominio.SolicitudPago;
import com.duoc.bancoxyz.pagos.dominio.Pago;
import com.duoc.bancoxyz.pagos.repositorio.PagoDao;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Orquesta el inicio de la saga. */
@Service
public class PagoServicio {

    private static final Logger log = LoggerFactory.getLogger(PagoServicio.class);

    private final PagoDao pagos;
    private final ClienteCuentas cuentas;
    private final RegistradorDePagos registrador;
    private final String moneda;

    public PagoServicio(PagoDao pagos,
                               ClienteCuentas cuentas,
                               RegistradorDePagos registrador,
                               @Value("${banco.moneda:CLP}") String moneda) {
        this.pagos = pagos;
        this.cuentas = cuentas;
        this.registrador = registrador;
        this.moneda = moneda;
    }

    /**
     * Registra el pago y publica el hecho.
     *
     * <p>Devuelve siempre en estado PENDIENTE: este servicio no sabe todavia si
     * la operacion prosperara, y fingir lo contrario -responder "listo" antes de
     * que el saldo haya cambiado- seria mentirle al canal. El estado real llega
     * despues, por evento.</p>
     */
    public ResultadoCreacion crear(SolicitudPago solicitud) {
        // Idempotencia por referencia del canal: un reintento no crea una
        // segunda pago ni publica un segundo evento, devuelve la original.
        Optional<Pago> yaExistente = pagos.buscarPorReferencia(solicitud.referencia());
        if (yaExistente.isPresent()) {
            log.info("La referencia {} ya corresponde a el pago {}; no se crea otra",
                    solicitud.referencia(), yaExistente.get().id());
            return new ResultadoCreacion(yaExistente.get(), true,
                    "Ya existia un pago con esta referencia; se devuelve la original");
        }

        // Comprobacion anticipada. Si ms-cuentas no responde, el respaldo deja
        // seguir: la decision definitiva la toma ese servicio al aplicar el evento.
        ValidacionPrevia validacion = cuentas.validarCuenta(solicitud.cuentaId()).join();

        String id = "PG-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Pago pago = new Pago(
                id, solicitud.cuentaId(), solicitud.tipo(), solicitud.monto(), moneda,
                solicitud.canal(), EstadoPago.PENDIENTE, null, null, null,
                solicitud.referencia(), Instant.now(), null);

        registrador.registrarYPublicar(pago);

        log.info("Pago {} creado en estado PENDIENTE sobre la cuenta {} por {} {} ({})",
                id, solicitud.cuentaId(), solicitud.monto(), moneda, validacion.detalle());

        return new ResultadoCreacion(pago, false, validacion.detalle());
    }

    public Pago obtener(String id) {
        return pagos.buscar(id)
                .orElseThrow(() -> new PagoNoEncontradoException(id));
    }

    public List<Pago> listar(int limite) {
        return pagos.listar(Math.clamp(limite, 1, 200));
    }

    public List<Pago> listarDeCuenta(int cuentaId, int limite) {
        return pagos.listarDeCuenta(cuentaId, Math.clamp(limite, 1, 200));
    }

    /**
     * @param pago  el pago resultante
     * @param yaExistia    si la referencia ya habia sido usada
     * @param observacion  detalle de la comprobacion previa, para la evidencia
     */
    public record ResultadoCreacion(Pago pago, boolean yaExistia, String observacion) {
    }
}
