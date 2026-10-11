package com.duoc.bancoxyz.pagos.repositorio;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bitacora de eventos de resultado ya procesados.
 *
 * <p>Los eventos de vuelta tambien pueden repetirse, de modo que el servicio que
 * los origino necesita la misma proteccion que el que los aplica.</p>
 */
@Repository
public class EventoProcesadoDao {

    private final JdbcTemplate jdbc;

    public EventoProcesadoDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} si el evento es nuevo y debe procesarse */
    public boolean reservar(String eventoId, String topico) {
        try {
            jdbc.update("INSERT INTO evento_procesado (evento_id, topico) VALUES (?, ?)",
                    eventoId, topico);
            return true;
        } catch (DuplicateKeyException yaEstaba) {
            return false;
        }
    }
}
