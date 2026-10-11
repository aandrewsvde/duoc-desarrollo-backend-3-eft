package com.duoc.bancoxyz.pagos.servicio;

import com.duoc.bancoxyz.eventos.Topicos;
import com.duoc.bancoxyz.eventos.PagoRegistrado;
import com.duoc.bancoxyz.pagos.dominio.Pago;
import com.duoc.bancoxyz.pagos.mensajeria.PublicadorDeEventos;
import com.duoc.bancoxyz.pagos.repositorio.PagoDao;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarda el pago y publica su evento como una sola unidad.
 *
 * <p>Vive en un componente aparte de {@link PagoServicio} por una razon
 * concreta: {@code @Transactional} actua mediante un proxy, y una llamada entre
 * metodos de la misma clase no pasa por el. Si este metodo estuviera junto al
 * que lo invoca, la anotacion no haria nada y el fallo seria silencioso -todo
 * parece funcionar hasta que una publicacion falla y la fila queda escrita de
 * todos modos-.</p>
 */
@Component
public class RegistradorDePagos {

    private final PagoDao pagos;
    private final PublicadorDeEventos publicador;

    public RegistradorDePagos(PagoDao pagos, PublicadorDeEventos publicador) {
        this.pagos = pagos;
        this.publicador = publicador;
    }

    /**
     * Inserta como PENDIENTE y publica el hecho.
     *
     * <p>El orden importa: primero la fila, despues el evento. Al reves, una
     * caida entre ambos pasos dejaria un evento en Kafka para un pago
     * que no existe en ninguna base y que nadie podria confirmar ni compensar.</p>
     *
     * <p>La publicacion va dentro de la transaccion de base de datos para que
     * un fallo al enviar revierta la insercion y el canal reciba un error
     * claro, en lugar de quedarse con un pago registrado que nadie procesara.</p>
     */
    @Transactional
    public void registrarYPublicar(Pago pago) {
        pagos.insertarPendiente(pago);
        publicador.publicarYEsperar(Topicos.PAGOS_REGISTRADOS, pago.id(),
                new PagoRegistrado(
                        UUID.randomUUID().toString(),
                        pago.id(),
                        pago.cuentaId(),
                        pago.tipo(),
                        pago.monto(),
                        pago.moneda(),
                        pago.canal(),
                        Instant.now()));
    }
}
