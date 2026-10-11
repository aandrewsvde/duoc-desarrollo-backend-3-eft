package com.duoc.bancoxyz.clientes.repositorio;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bitacora de eventos ya aplicados.
 *
 * <p>Kafka entrega "al menos una vez": tras un reequilibrio de particiones o un
 * reinicio, el mismo evento puede llegar dos veces. Sin este registro, el saldo
 * consolidado se recalcularia sobre datos repetidos.</p>
 */
@Repository
public class EventoProcesadoDao {

    private final JdbcTemplate jdbc;

    public EventoProcesadoDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Intenta reservar el evento.
     *
     * <p>Se apoya en la clave primaria en lugar de hacer un SELECT previo:
     * entre la consulta y la insercion otro consumidor podria colarse, y esa
     * ventana es justamente donde aparecen los dobles conteos.</p>
     *
     * @return {@code true} si este consumidor gano la reserva y debe procesarlo
     */
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
