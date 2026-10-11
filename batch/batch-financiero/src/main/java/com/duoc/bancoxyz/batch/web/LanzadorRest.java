package com.duoc.bancoxyz.batch.web;

import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import com.duoc.bancoxyz.batch.config.FabricaDeJobs;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lanza los jobs por HTTP y consulta sus ejecuciones.
 *
 * <p>Un proceso batch que solo se puede disparar entrando al contenedor no se
 * integra con nada. Con este endpoint lo puede lanzar un planificador externo
 * -EventBridge, un cron, un operador desde una consola- sin acceso a la
 * maquina, que es como se opera un batch en la nube.</p>
 *
 * <p>No lleva autenticacion OAuth 2.0, y es una decision consciente con un
 * limite claro: este servicio NO se publica en el gateway ni expone su puerto
 * fuera de la red de contenedores, de modo que solo lo alcanza quien ya esta
 * dentro. En un despliegue real iria detras del mismo servidor de autorizacion
 * que el resto, con un alcance propio; se documenta como el primer pendiente
 * de seguridad en lugar de aparentar que esta resuelto.</p>
 */
@RestController
@RequestMapping("/api/batch")
public class LanzadorRest {

    private static final Logger log = LoggerFactory.getLogger(LanzadorRest.class);

    /**
     * Orden en que deben ejecutarse.
     *
     * <p>No es arbitrario: intereses crea las cuentas, y tanto los movimientos
     * anuales como las transacciones diarias las referencian. Lanzarlos al
     * reves deja los dos ultimos sin nada que migrar.</p>
     */
    private static final List<String> ORDEN = List.of("intereses", "estados", "transacciones");

    private final JobLauncher jobLauncher;
    private final FabricaDeJobs fabrica;
    private final BancoBatchProperties propiedades;
    private final JdbcTemplate jdbcTemplate;

    public LanzadorRest(JobLauncher jobLauncher, FabricaDeJobs fabrica,
                        BancoBatchProperties propiedades, JdbcTemplate jdbcTemplate) {
        this.jobLauncher = jobLauncher;
        this.fabrica = fabrica;
        this.propiedades = propiedades;
        this.jdbcTemplate = jdbcTemplate;
    }

    private Job construir(String nombre, int gridSize, int chunkSize) {
        return switch (nombre) {
            case "transacciones" -> fabrica.reporteTransaccionesDiarias(gridSize, chunkSize);
            case "intereses" -> fabrica.calculoInteresesMensuales(gridSize, chunkSize);
            case "estados" -> fabrica.estadosCuentaAnuales(gridSize, chunkSize);
            default -> throw new IllegalArgumentException(
                    "Job desconocido: " + nombre + ". Use intereses, estados o transacciones.");
        };
    }

    @PostMapping("/jobs/{nombre}")
    public ResponseEntity<Map<String, Object>> lanzar(
            @PathVariable String nombre,
            @RequestParam(required = false) Integer gridSize,
            @RequestParam(required = false) Integer chunkSize) {

        int grid = gridSize != null ? gridSize : propiedades.getParticion().getGridSize();
        int chunk = chunkSize != null ? chunkSize : propiedades.getParticion().getChunkSize();

        try {
            Job job = construir(nombre, grid, chunk);
            // El parametro de tiempo hace unica cada ejecucion. Spring Batch se
            // niega a repetir un job con los mismos parametros si ya termino
            // bien, que es correcto en produccion y un estorbo al demostrar.
            JobExecution ejecucion = jobLauncher.run(job, new JobParametersBuilder()
                    .addLong("lanzadoEn", System.currentTimeMillis())
                    .addLong("gridSize", (long) grid)
                    .addLong("chunkSize", (long) chunk)
                    .toJobParameters());

            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("job", nombre);
            cuerpo.put("ejecucionId", ejecucion.getId());
            cuerpo.put("estado", ejecucion.getStatus().toString());
            cuerpo.put("gridSize", grid);
            cuerpo.put("chunkSize", chunk);
            cuerpo.put("pasos", ejecucion.getStepExecutions().stream()
                    .map(paso -> Map.of(
                            "nombre", paso.getStepName(),
                            "leidos", paso.getReadCount(),
                            "escritos", paso.getWriteCount(),
                            "omitidos", paso.getSkipCount(),
                            "estado", paso.getStatus().toString()))
                    .toList());
            return ResponseEntity.ok(cuerpo);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("El job {} fallo", nombre, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error("El job " + nombre + " fallo: " + e.getMessage()));
        }
    }

    /**
     * Ejecuta los tres en el orden correcto.
     *
     * <p>Se detiene en el primero que no termine bien. Seguir adelante cargaria
     * movimientos sobre cuentas que no se crearon, y el fallo real quedaria
     * sepultado bajo una cascada de errores de clave foranea.</p>
     */
    @PostMapping("/migracion-completa")
    public ResponseEntity<Map<String, Object>> migracionCompleta() {
        List<Map<String, Object>> resultados = new java.util.ArrayList<>();

        for (String nombre : ORDEN) {
            ResponseEntity<Map<String, Object>> respuesta = lanzar(nombre, null, null);
            Map<String, Object> cuerpo = respuesta.getBody();
            resultados.add(cuerpo);

            boolean termino = cuerpo != null && "COMPLETED".equals(cuerpo.get("estado"));
            if (!termino) {
                Map<String, Object> fallo = new LinkedHashMap<>();
                fallo.put("resultado", "INTERRUMPIDA");
                fallo.put("detenida_en", nombre);
                fallo.put("ejecutados", resultados);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(fallo);
            }
        }

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("resultado", "COMPLETADA");
        cuerpo.put("orden", ORDEN);
        cuerpo.put("ejecutados", resultados);
        return ResponseEntity.ok(cuerpo);
    }

    @GetMapping("/ejecuciones")
    public Map<String, Object> ejecuciones(@RequestParam(defaultValue = "10") int limite) {
        List<Map<String, Object>> filas = jdbcTemplate.queryForList("""
                SELECT e.job_execution_id, i.job_name, e.status, e.exit_code,
                       e.start_time, e.end_time
                  FROM batch_job_execution e
                  JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                 ORDER BY e.job_execution_id DESC
                 LIMIT ?
                """, Math.clamp(limite, 1, 50));

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("total", filas.size());
        cuerpo.put("ejecuciones", filas);
        return cuerpo;
    }

    private Map<String, Object> error(String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("error", mensaje);
        cuerpo.put("instante", Instant.now().toString());
        return cuerpo;
    }
}
