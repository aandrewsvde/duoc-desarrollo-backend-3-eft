package com.duoc.bancoxyz.cuentas.repositorio;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bitacora de eventos ya aplicados.
 *
 * <p>Es el mecanismo de idempotencia del consumidor. Kafka entrega "al menos
 * una vez", de modo que tras un reequilibrio de particiones o un reinicio el
 * mismo evento puede llegar de nuevo; sin este registro, el dinero se
 * descontaria dos veces.</p>
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
     * <p>Se apoya en la clave primaria en lugar de hacer un SELECT previo: entre
     * la consulta y la insercion otro consumidor podria colarse, y la ventana es
     * justamente donde aparecen los dobles cargos. Aqui la condicion de carrera
     * no existe porque quien pierde recibe la violacion de clave.</p>
     *
     * @return {@code true} si este consumidor gano la reserva y debe procesarlo
     */
    public boolean reservar(String eventoId, String topico) {
        try {
            jdbc.update("""
                    INSERT INTO evento_procesado (evento_id, topico, resultado)
                    VALUES (?, ?, 'EN_CURSO')
                    """, eventoId, topico);
            return true;
        } catch (DuplicateKeyException yaEstaba) {
            return false;
        }
    }

    public void marcarResultado(String eventoId, String resultado) {
        jdbc.update("UPDATE evento_procesado SET resultado = ? WHERE evento_id = ?",
                resultado, eventoId);
    }

    public int total() {
        Integer cuantos = jdbc.queryForObject("SELECT count(*) FROM evento_procesado", Integer.class);
        return cuantos == null ? 0 : cuantos;
    }
}
