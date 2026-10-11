package com.duoc.bancoxyz.clientes.web;

import com.duoc.bancoxyz.clientes.dominio.Cliente;
import com.duoc.bancoxyz.clientes.dominio.SolicitudCliente;
import com.duoc.bancoxyz.clientes.servicio.ClienteServicio;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de gestion de clientes. */
@RestController
@RequestMapping("/api/clientes")
@Tag(name = "Clientes", description = "Alta, consulta y mantenimiento de titulares")
public class ClienteControlador {

    private final ClienteServicio servicio;
    private final String instancia;

    public ClienteControlador(ClienteServicio servicio, @Value("${server.port}") String puerto,
                             @Value("${spring.cloud.client.ip-address:local}") String direccion) {
        this.servicio = servicio;
        // Identifica que replica respondio. Con varias instancias detras del
        // balanceador es la unica forma de ver, desde fuera, que el escalado
        // horizontal esta repartiendo de verdad.
                // Identifica QUE REPLICA respondio, no solo el servicio.
        //
        // Con varias instancias detras del balanceador, un identificador que
        // solo lleve el puerto es identico en todas y no distingue nada: el
        // reparto podria no estar ocurriendo y la evidencia lo mostraria igual.
        // La direccion si es unica por contenedor, y es la misma con la que
        // cada instancia se registra en Eureka.
        this.instancia = "ms-clientes:" + direccion + ":" + puerto;
    }

    @PostMapping
    @Operation(summary = "Registra un cliente nuevo")
    public ResponseEntity<Cliente> registrar(@Valid @RequestBody SolicitudCliente solicitud) {
        Cliente cliente = servicio.registrar(solicitud);
        return ResponseEntity.created(URI.create("/api/clientes/" + cliente.id())).body(cliente);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene un cliente con su segmento y saldo consolidado")
    public Cliente obtener(@PathVariable int id) {
        return servicio.obtener(id);
    }

    @GetMapping
    @Operation(summary = "Lista clientes, opcionalmente filtrados por nombre")
    public Map<String, Object> listar(@RequestParam(required = false) String nombre,
                                      @RequestParam(defaultValue = "50") int limite) {
        List<Cliente> clientes = (nombre == null || nombre.isBlank())
                ? servicio.listar(limite)
                : servicio.buscarPorNombre(nombre, limite);

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("instancia", instancia);
        cuerpo.put("total", clientes.size());
        cuerpo.put("clientes", clientes);
        return cuerpo;
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualiza el perfil de un cliente")
    public Cliente actualizar(@PathVariable int id, @Valid @RequestBody SolicitudCliente solicitud) {
        return servicio.actualizarPerfil(id, solicitud);
    }

    /**
     * Da de baja a un cliente.
     *
     * <p>Responde 200 con el cliente y no 204 sin cuerpo: la baja es logica, el
     * recurso sigue existiendo y quien llama debe poder comprobar en que estado
     * quedo. Un 204 sugeriria que se borro.</p>
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Da de baja logica a un cliente, conservando su historial")
    public Cliente darDeBaja(@PathVariable int id) {
        return servicio.darDeBaja(id);
    }
}
