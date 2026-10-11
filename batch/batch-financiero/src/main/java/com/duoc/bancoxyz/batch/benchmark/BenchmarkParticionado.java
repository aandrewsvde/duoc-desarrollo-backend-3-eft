package com.duoc.bancoxyz.batch.benchmark;

import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import com.duoc.bancoxyz.batch.config.FabricaDeJobs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Compara configuraciones de particionado para encontrar la optima.
 *
 * <p>Ejecuta cada job con todas las combinaciones de {@code gridSize} y
 * {@code chunkSize} configuradas, repitiendo cada una varias veces, y
 * publica una tabla comparativa en Markdown ademas de dejar cada corrida
 * en la tabla {@code benchmark_ejecucion}.</p>
 *
 * <p>Dos decisiones metodologicas importantes:</p>
 * <ul>
 *   <li>Se descarta una <b>corrida de calentamiento</b> por combinacion.
 *       La JVM compila en caliente y el pool de conexiones se llena en las
 *       primeras invocaciones; sin descartarla, la primera configuracion
 *       medida siempre parece la peor.</li>
 *   <li>Las repeticiones se <b>intercalan</b> entre configuraciones en vez
 *       de agotar una antes de pasar a la siguiente. Todas las corridas
 *       comparten el mismo repositorio de Spring Batch, que crece con cada
 *       ejecucion; midiendo en bloques, esa deriva se cargaria entera sobre
 *       las ultimas configuraciones y las haria parecer peores de lo que
 *       son. Intercalando, el sesgo se reparte por igual.</li>
 *   <li>Se reporta la <b>mediana</b> ademas del promedio. Con volumenes
 *       pequenos, una pausa del recolector de basura en una sola
 *       repeticion desplaza el promedio varios milisegundos; la mediana
 *       resiste ese ruido.</li>
 * </ul>
 *
 * <p>La metrica principal es la duracion del <b>step de carga
 * particionado</b>, no la del job completo: los steps de preparacion,
 * agregacion e informe son secuenciales por naturaleza y su costo, fijo,
 * diluiria el efecto que se quiere medir.</p>
 */
@Component
public class BenchmarkParticionado {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkParticionado.class);
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JobLauncher jobLauncher;
    private final FabricaDeJobs fabrica;
    private final BancoBatchProperties propiedades;
    private final JdbcTemplate jdbcTemplate;

    public BenchmarkParticionado(JobLauncher jobLauncher, FabricaDeJobs fabrica,
                                 BancoBatchProperties propiedades, JdbcTemplate jdbcTemplate) {
        this.jobLauncher = jobLauncher;
        this.fabrica = fabrica;
        this.propiedades = propiedades;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ejecutar() throws Exception {
        BancoBatchProperties.Benchmark config = propiedades.getBenchmark();

        Map<String, BiFunction<Integer, Integer, Job>> procesos = new LinkedHashMap<>();
        procesos.put(FabricaDeJobs.JOB_TRANSACCIONES, fabrica::reporteTransaccionesDiarias);
        procesos.put(FabricaDeJobs.JOB_INTERESES, fabrica::calculoInteresesMensuales);
        procesos.put(FabricaDeJobs.JOB_ESTADOS_CUENTA, fabrica::estadosCuentaAnuales);

        Map<String, String> stepDeCarga = Map.of(
                FabricaDeJobs.JOB_TRANSACCIONES, FabricaDeJobs.STEP_CARGA_TRANSACCIONES,
                FabricaDeJobs.JOB_INTERESES, FabricaDeJobs.STEP_CARGA_INTERESES,
                FabricaDeJobs.JOB_ESTADOS_CUENTA, FabricaDeJobs.STEP_CARGA_MOVIMIENTOS);

        jdbcTemplate.update("DELETE FROM benchmark_ejecucion");

        List<MedicionParticionado> mediciones = new ArrayList<>();
        int totalCombinaciones = config.getGridSizes().size() * config.getChunkSizes().size();
        log.info("Benchmark: {} jobs x {} combinaciones x {} repeticiones intercaladas "
                        + "(mas una pasada de calentamiento por job)",
                procesos.size(), totalCombinaciones, config.getRepeticiones());

        for (Map.Entry<String, BiFunction<Integer, Integer, Job>> proceso : procesos.entrySet()) {
            String jobName = proceso.getKey();
            String nombreStepCarga = stepDeCarga.get(jobName);

            List<int[]> combinaciones = new ArrayList<>();
            for (int grid : config.getGridSizes()) {
                for (int chunk : config.getChunkSizes()) {
                    combinaciones.add(new int[]{grid, chunk});
                }
            }

            // Pasada de calentamiento sobre todas las combinaciones.
            for (int[] combinacion : combinaciones) {
                correr(proceso.getValue().apply(combinacion[0], combinacion[1]),
                        combinacion[0], combinacion[1], nombreStepCarga, jobName, 0);
            }

            // Repeticiones intercaladas: una vuelta completa por repeticion.
            for (int repeticion = 1; repeticion <= config.getRepeticiones(); repeticion++) {
                for (int[] combinacion : combinaciones) {
                    MedicionParticionado medicion = correr(
                            proceso.getValue().apply(combinacion[0], combinacion[1]),
                            combinacion[0], combinacion[1], nombreStepCarga, jobName, repeticion);
                    mediciones.add(medicion);
                    registrar(medicion);
                }
                log.info("{}: repeticion {} de {} completada sobre {} configuraciones",
                        jobName, repeticion, config.getRepeticiones(), combinaciones.size());
            }

            for (int[] combinacion : combinaciones) {
                log.info("Medido {} | grid={} chunk={} -> carga mediana {} ms",
                        jobName, combinacion[0], combinacion[1],
                        medianaCarga(mediciones, jobName, combinacion[0], combinacion[1]));
            }
        }

        escribirInforme(mediciones);
    }

    private MedicionParticionado correr(Job job, int grid, int chunk, String nombreStepCarga,
                                        String jobName, int repeticion) throws Exception {
        long inicio = System.nanoTime();
        JobExecution ejecucion = jobLauncher.run(job, new JobParametersBuilder()
                .addLong("gridSize", (long) grid)
                .addLong("chunkSize", (long) chunk)
                .addLong("repeticion", (long) repeticion)
                .addLong("lanzadoEn", System.currentTimeMillis())
                .toJobParameters());
        long duracionJob = (System.nanoTime() - inicio) / 1_000_000L;

        StepExecution carga = ejecucion.getStepExecutions().stream()
                .filter(se -> nombreStepCarga.equals(se.getStepName()))
                .findFirst()
                .orElse(null);

        long duracionCarga = (carga == null || carga.getStartTime() == null || carga.getEndTime() == null)
                ? 0L
                : Duration.between(carga.getStartTime(), carga.getEndTime()).toMillis();

        return new MedicionParticionado(
                jobName, grid, chunk, repeticion, duracionJob, duracionCarga,
                carga == null ? 0 : carga.getReadCount(),
                carga == null ? 0 : carga.getWriteCount(),
                carga == null ? 0 : carga.getReadSkipCount(),
                carga == null ? 0 : carga.getProcessSkipCount() + carga.getWriteSkipCount(),
                carga == null ? 0 : carga.getCommitCount(),
                ejecucion.getStatus().name());
    }

    private void registrar(MedicionParticionado m) {
        jdbcTemplate.update("""
                INSERT INTO benchmark_ejecucion
                    (job_name, grid_size, chunk_size, repeticion, duracion_ms,
                     leidos, escritos, omitidos, commits, estado)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                m.jobName(), m.gridSize(), m.chunkSize(), m.repeticion(), m.duracionCargaMs(),
                (int) m.leidos(), (int) m.escritos(), (int) m.omitidos(), (int) m.commits(), m.estado());
    }

    // -----------------------------------------------------------------
    // Informe comparativo
    // -----------------------------------------------------------------
    private void escribirInforme(List<MedicionParticionado> mediciones) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Comparacion de configuraciones de particionado\n\n");
        md.append("Generado: ").append(LocalDateTime.now().format(SELLO)).append("  \n");
        md.append("Repeticiones medidas por combinacion: ").append(propiedades.getBenchmark().getRepeticiones())
                .append(" (mas una corrida de calentamiento descartada)  \n");
        md.append("Nucleos disponibles: ").append(Runtime.getRuntime().availableProcessors())
                .append(" | pool de particiones: core=").append(propiedades.getParticion().getCorePoolSize())
                .append(", max=").append(propiedades.getParticion().getMaxPoolSize()).append("  \n");
        md.append("Metrica: duracion del step de carga particionado (ms).\n\n");

        List<String> jobs = mediciones.stream().map(MedicionParticionado::jobName).distinct().toList();

        for (String jobName : jobs) {
            List<MedicionParticionado> delJob = mediciones.stream()
                    .filter(m -> m.jobName().equals(jobName)).toList();

            long registros = delJob.stream().mapToLong(MedicionParticionado::registrosDeEntrada).max().orElse(0);
            md.append("## ").append(jobName).append("\n\n");
            md.append("Registros de entrada por corrida: ").append(registros).append("\n\n");
            md.append("| gridSize | chunkSize | mediana (ms) | promedio (ms) | min | max | speedup vs grid=1 |\n");
            md.append("|---|---|---|---|---|---|---|\n");

            List<Integer> grids = delJob.stream().map(MedicionParticionado::gridSize).distinct().sorted().toList();
            List<Integer> chunks = delJob.stream().map(MedicionParticionado::chunkSize).distinct().sorted().toList();

            Map<Integer, Double> referenciaPorChunk = new LinkedHashMap<>();
            for (int chunk : chunks) {
                referenciaPorChunk.put(chunk, mediana(filtrar(delJob, grids.get(0), chunk)));
            }

            double mejor = Double.MAX_VALUE;
            String mejorConfig = "";
            for (int grid : grids) {
                for (int chunk : chunks) {
                    List<MedicionParticionado> celda = filtrar(delJob, grid, chunk);
                    if (celda.isEmpty()) {
                        continue;
                    }
                    double mediana = mediana(celda);
                    double promedio = celda.stream().mapToLong(MedicionParticionado::duracionCargaMs).average().orElse(0);
                    long min = celda.stream().mapToLong(MedicionParticionado::duracionCargaMs).min().orElse(0);
                    long max = celda.stream().mapToLong(MedicionParticionado::duracionCargaMs).max().orElse(0);
                    double referencia = referenciaPorChunk.getOrDefault(chunk, mediana);
                    double speedup = mediana == 0 ? 0 : referencia / mediana;

                    md.append(String.format("| %d | %d | %.1f | %.1f | %d | %d | %.2fx |%n",
                            grid, chunk, mediana, promedio, min, max, speedup));

                    if (mediana < mejor) {
                        mejor = mediana;
                        mejorConfig = "gridSize=" + grid + ", chunkSize=" + chunk;
                    }
                }
            }
            md.append("\n**Configuracion optima observada: ").append(mejorConfig)
                    .append("** (mediana ").append(String.format("%.1f", mejor)).append(" ms)\n\n");
            log.info("Configuracion optima para {}: {} ({} ms de mediana)",
                    jobName, mejorConfig, String.format("%.1f", mejor));
        }

        Path destino = Paths.get(propiedades.getBenchmark().getArchivoSalida());
        if (destino.getParent() != null) {
            Files.createDirectories(destino.getParent());
        }
        Files.writeString(destino, md.toString(), StandardCharsets.UTF_8);
        log.info("Informe comparativo escrito en {}", destino.toAbsolutePath());
    }

    private static List<MedicionParticionado> filtrar(List<MedicionParticionado> mediciones, int grid, int chunk) {
        return mediciones.stream()
                .filter(m -> m.gridSize() == grid && m.chunkSize() == chunk)
                .toList();
    }

    private static double mediana(List<MedicionParticionado> mediciones) {
        List<Long> valores = mediciones.stream()
                .map(MedicionParticionado::duracionCargaMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (valores.isEmpty()) {
            return 0d;
        }
        int medio = valores.size() / 2;
        return valores.size() % 2 == 1
                ? valores.get(medio)
                : (valores.get(medio - 1) + valores.get(medio)) / 2.0;
    }

    private static String medianaCarga(List<MedicionParticionado> mediciones, String jobName, int grid, int chunk) {
        return String.format("%.1f", mediana(mediciones.stream()
                .filter(m -> m.jobName().equals(jobName) && m.gridSize() == grid && m.chunkSize() == chunk)
                .toList()));
    }
}
