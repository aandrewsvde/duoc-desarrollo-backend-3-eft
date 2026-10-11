package com.duoc.bancoxyz.batch.runner;

import com.duoc.bancoxyz.batch.benchmark.BenchmarkParticionado;
import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import com.duoc.bancoxyz.batch.config.FabricaDeJobs;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Punto de entrada por linea de comandos.
 *
 * <pre>
 *   --job=transacciones|intereses|estados|todos   proceso a ejecutar
 *   --gridSize=N                                  numero de particiones
 *   --chunkSize=N                                 tamano del chunk
 *   --benchmark                                   compara configuraciones de particionado
 *   --restart                                     reanuda la ejecucion fallida en vez de crear una nueva
 * </pre>
 *
 * <p>El arranque automatico de jobs de Spring Boot esta desactivado
 * ({@code spring.batch.job.enabled=false}): en un batch de produccion es
 * el planificador quien decide que proceso corre y con que parametros, no
 * el hecho de que la aplicacion levante.</p>
 */
@Component
public class LanzadorDeJobs implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(LanzadorDeJobs.class);

    private final JobLauncher jobLauncher;
    private final FabricaDeJobs fabrica;
    private final BancoBatchProperties propiedades;
    private final BenchmarkParticionado benchmark;

    private int codigoDeSalida = 0;

    public LanzadorDeJobs(JobLauncher jobLauncher, FabricaDeJobs fabrica,
                          BancoBatchProperties propiedades, BenchmarkParticionado benchmark) {
        this.jobLauncher = jobLauncher;
        this.fabrica = fabrica;
        this.propiedades = propiedades;
        this.benchmark = benchmark;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Sin argumentos no se ejecuta nada y el servicio queda escuchando.
        //
        // En la Experiencia 1 este modulo era un proceso suelto que se lanzaba
        // desde la linea de ordenes; aqui es un contenedor que arranca con el
        // resto del sistema. Si ejecutara los jobs al iniciar, cada reinicio
        // reprocesaria el legado entero y, al terminar, el contenedor se
        // apagaria. La via normal ahora es el endpoint REST; los argumentos se
        // conservan para poder ejecutarlo a mano dentro del contenedor.
        if (!args.containsOption("job") && !args.containsOption("benchmark")) {
            log.info("Sin argumento --job: el servicio queda a la espera. "
                    + "Lance la migracion con POST /api/batch/migracion-completa "
                    + "o un job concreto con POST /api/batch/jobs/{nombre}.");
            return;
        }

        if (args.containsOption("benchmark")) {
            benchmark.ejecutar();
            return;
        }

        String seleccion = valor(args, "job", "todos").toLowerCase();
        int gridSize = entero(args, "gridSize", propiedades.getParticion().getGridSize());
        int chunkSize = entero(args, "chunkSize", propiedades.getParticion().getChunkSize());

        log.info("Ejecutando '{}' con gridSize={} y chunkSize={}", seleccion, gridSize, chunkSize);

        List<Job> jobs = switch (seleccion) {
            case "transacciones" -> List.of(fabrica.reporteTransaccionesDiarias(gridSize, chunkSize));
            case "intereses" -> List.of(fabrica.calculoInteresesMensuales(gridSize, chunkSize));
            case "estados", "cuentas" -> List.of(fabrica.estadosCuentaAnuales(gridSize, chunkSize));
            case "todos" -> List.of(
                    fabrica.reporteTransaccionesDiarias(gridSize, chunkSize),
                    fabrica.calculoInteresesMensuales(gridSize, chunkSize),
                    fabrica.estadosCuentaAnuales(gridSize, chunkSize));
            default -> throw new IllegalArgumentException(
                    "Job desconocido: '" + seleccion + "'. Use transacciones | intereses | estados | todos");
        };

        boolean reanudar = args.containsOption("restart");
        if (reanudar) {
            log.info("Modo reanudacion: se reutilizan los parametros identificatorios para retomar "
                    + "la ejecucion fallida desde el step donde quedo, en vez de crear una instancia nueva.");
        }

        for (Job job : jobs) {
            JobParametersBuilder constructor = new JobParametersBuilder()
                    .addLong("gridSize", (long) gridSize)
                    .addLong("chunkSize", (long) chunkSize);
            if (!reanudar) {
                // Marca de tiempo identificatoria: cada lanzamiento normal es una
                // instancia nueva. Al reanudar se omite a proposito, porque es la
                // igualdad de parametros lo que le indica a Spring Batch que debe
                // retomar la ejecucion anterior y no empezar de cero.
                constructor.addLong("lanzadoEn", System.currentTimeMillis());
            }
            JobParameters parametros = constructor.toJobParameters();

            JobExecution ejecucion = jobLauncher.run(job, parametros);
            if (ejecucion.getStatus() != BatchStatus.COMPLETED) {
                codigoDeSalida = 1;
            }
        }
    }

    @Override
    public int getExitCode() {
        return codigoDeSalida;
    }

    private static String valor(ApplicationArguments args, String nombre, String porDefecto) {
        List<String> valores = args.getOptionValues(nombre);
        return (valores == null || valores.isEmpty()) ? porDefecto : valores.get(0);
    }

    private static int entero(ApplicationArguments args, String nombre, int porDefecto) {
        List<String> valores = args.getOptionValues(nombre);
        if (valores == null || valores.isEmpty()) {
            return porDefecto;
        }
        return Integer.parseInt(valores.get(0).trim());
    }
}
