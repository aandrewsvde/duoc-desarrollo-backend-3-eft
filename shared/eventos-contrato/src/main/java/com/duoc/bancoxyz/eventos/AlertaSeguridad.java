package com.duoc.bancoxyz.eventos;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Algo relevante para la seguridad ocurrio en algun servicio.
 *
 * <p>Viaja por un topico propio y no junto a los eventos de negocio porque
 * tiene otro publico y otra vida util: a los pagos los consumen servicios que
 * mueven dinero, y a estas alertas las consume quien vigila. Mezclarlos
 * obligaria a cada consumidor a filtrar lo que no le incumbe.</p>
 *
 * <p>El campo {@code detalle} NO debe llevar datos personales ni credenciales.
 * Una alerta de seguridad que filtra justamente lo que protege es peor que no
 * tenerla, porque acaba replicada en todos los registros del sistema.</p>
 *
 * @param severidad INFORMATIVA, ADVERTENCIA o CRITICA
 * @param origen    servicio que la emite
 * @param tipo      codigo del hecho, por ejemplo SALDO_INSUFICIENTE_REITERADO
 */
public record AlertaSeguridad(
        String eventoId,
        String severidad,
        String origen,
        String tipo,
        String detalle,
        Integer cuentaId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant ocurridoEn) {
}
