package com.duoc.bancoxyz.clientes.servicio;

import com.duoc.bancoxyz.clientes.dominio.Cliente;
import com.duoc.bancoxyz.clientes.dominio.ClienteNoEncontradoException;
import com.duoc.bancoxyz.clientes.dominio.SolicitudCliente;
import com.duoc.bancoxyz.clientes.repositorio.ClienteDao;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alta, consulta y mantenimiento del perfil de los clientes. */
@Service
public class ClienteServicio {

    private static final Logger log = LoggerFactory.getLogger(ClienteServicio.class);

    private final ClienteDao clientes;

    public ClienteServicio(ClienteDao clientes) {
        this.clientes = clientes;
    }

    @Transactional
    public Cliente registrar(SolicitudCliente solicitud) {
        int id = clientes.siguienteId();
        clientes.insertar(id, solicitud.nombre().trim(), solicitud.edad());
        // El registro no incluye el nombre: un identificador basta para seguir
        // la operacion, y escribir datos personales en los logs los replica en
        // cada sistema de recoleccion por el que pasen.
        log.info("Cliente {} registrado", id);
        return clientes.buscar(id).orElseThrow(() -> new IllegalStateException(
                "El cliente " + id + " no se pudo releer despues de crearlo"));
    }

    public Cliente obtener(int id) {
        return clientes.buscar(id).orElseThrow(() -> new ClienteNoEncontradoException(id));
    }

    public List<Cliente> listar(int limite) {
        return clientes.listar(Math.clamp(limite, 1, 200));
    }

    public List<Cliente> buscarPorNombre(String fragmento, int limite) {
        return clientes.buscarPorNombre(fragmento, Math.clamp(limite, 1, 200));
    }

    @Transactional
    public Cliente actualizarPerfil(int id, SolicitudCliente solicitud) {
        int filas = clientes.actualizarPerfil(id, solicitud.nombre().trim(), solicitud.edad());
        if (filas != 1) {
            // O no existe, o esta de baja. Se distingue para que el mensaje
            // diga la verdad en vez de un "no encontrado" generico.
            Cliente existente = clientes.buscar(id)
                    .orElseThrow(() -> new ClienteNoEncontradoException(id));
            throw new EstadoClienteInvalidoException(
                    "El cliente " + id + " esta de " + existente.estado().toLowerCase()
                    + " y su perfil ya no se modifica");
        }
        log.info("Perfil del cliente {} actualizado", id);
        return obtener(id);
    }

    /**
     * Da de baja a un cliente.
     *
     * <p>Es una baja logica, no un borrado. Las cuentas y los pagos que ese
     * cliente genero siguen existiendo en los otros servicios y deben poder
     * explicarse: borrar la fila dejaria huerfanos registros financieros que
     * por ley hay que conservar.</p>
     */
    @Transactional
    public Cliente darDeBaja(int id) {
        Cliente cliente = obtener(id);
        if ("BAJA".equals(cliente.estado())) {
            throw new EstadoClienteInvalidoException("El cliente " + id + " ya estaba de baja");
        }
        clientes.cambiarEstado(id, "BAJA");
        log.info("Cliente {} dado de baja", id);
        return obtener(id);
    }
}
