package com.duoc.bancoxyz.batch.config;

import com.duoc.bancoxyz.batch.decider.CalidadDatosDecider;
import com.duoc.bancoxyz.batch.migracion.MigracionClientesYCuentasTasklet;
import com.duoc.bancoxyz.batch.migracion.MigracionMovimientosTasklet;
import com.duoc.bancoxyz.batch.migracion.MigracionPagosTasklet;
import com.duoc.bancoxyz.batch.domain.CuentaInteres;
import com.duoc.bancoxyz.batch.domain.EstadoCuentaAnual;
import com.duoc.bancoxyz.batch.domain.InteresCrudo;
import com.duoc.bancoxyz.batch.domain.MovimientoAnual;
import com.duoc.bancoxyz.batch.domain.MovimientoCrudo;
import com.duoc.bancoxyz.batch.domain.Transaccion;
import com.duoc.bancoxyz.batch.domain.TransaccionCruda;
import com.duoc.bancoxyz.batch.listener.JobCompletionListener;
import com.duoc.bancoxyz.batch.listener.MetricasStepListener;
import com.duoc.bancoxyz.batch.listener.RegistroRechazadoSkipListener;
import com.duoc.bancoxyz.batch.partition.RangoArchivoPartitioner;
import com.duoc.bancoxyz.batch.policy.PoliticaOmisionPersonalizada;
import com.duoc.bancoxyz.batch.policy.PoliticaReintentoPersonalizada;
import com.duoc.bancoxyz.batch.processor.InteresItemProcessor;
import com.duoc.bancoxyz.batch.processor.MovimientoAnualItemProcessor;
import com.duoc.bancoxyz.batch.processor.TransaccionItemProcessor;
import com.duoc.bancoxyz.batch.tasklet.AlertaCalidadTasklet;
import com.duoc.bancoxyz.batch.tasklet.CompilacionEstadosTasklet;
import com.duoc.bancoxyz.batch.tasklet.DeduplicacionInteresesTasklet;
import com.duoc.bancoxyz.batch.tasklet.DeteccionAnomaliasTasklet;
import com.duoc.bancoxyz.batch.tasklet.LimpiezaTasklet;
import com.duoc.bancoxyz.batch.tasklet.ResumenDiarioTasklet;
import com.duoc.bancoxyz.batch.tasklet.ResumenInteresesTasklet;
import java.util.List;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Construye los tres jobs de la migracion.
 *
 * <p>Los jobs se arman <b>en el momento de lanzarlos</b> y no como beans
 * fijos. Esa decision es la que permite comparar configuraciones de
 * escalado: el tamano del grid y el del chunk son argumentos de
 * construccion, de modo que el benchmark puede levantar el mismo job con
 * distintos parametros dentro de un unico contexto de Spring, sin
 * reiniciar la aplicacion ni recurrir a beans de ambito job (que no se
 * resuelven en los hilos trabajadores de una particion).</p>
 *
 * <p>Estructura comun a los tres procesos:</p>
 * <pre>
 *   preparacion (tasklet, deja el destino limpio y el job re-ejecutable)
 *        v
 *   carga PARTICIONADA (chunk + tolerancia a fallos + cuarentena)
 *        v
 *   decider de calidad
 *        +-- CALIDAD_OK ............ agregacion -> informe -> COMPLETED
 *        +-- CALIDAD_INSUFICIENTE .. alerta -> FAILED
 * </pre>
 */
@Component
public class FabricaDeJobs {

    public static final String JOB_TRANSACCIONES = "reporteTransaccionesDiariasJob";
    public static final String JOB_INTERESES = "calculoInteresesMensualesJob";
    public static final String JOB_ESTADOS_CUENTA = "estadosCuentaAnualesJob";

    public static final String STEP_CARGA_TRANSACCIONES = "cargaTransaccionesStep";
    public static final String STEP_CARGA_INTERESES = "cargaInteresesStep";
    public static final String STEP_CARGA_MOVIMIENTOS = "cargaMovimientosAnualesStep";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final BancoBatchProperties propiedades;
    private final LectoresConfig lectores;
    private final RegistroRechazadoSkipListener registroRechazadoSkipListener;
    private final JobCompletionListener jobCompletionListener;
    private final MetricasStepListener metricasStepListener;
    private final org.springframework.core.task.TaskExecutor ejecutorDeParticiones;

    private final ItemReader<TransaccionCruda> lectorTransacciones;
    private final ItemReader<InteresCrudo> lectorIntereses;
    private final ItemReader<MovimientoCrudo> lectorCuentasAnuales;
    private final ItemReader<Object[]> lectorResumenDiario;
    private final ItemReader<EstadoCuentaAnual> lectorEstadosCuenta;

    private final ItemWriter<Transaccion> escritorTransacciones;
    private final ItemWriter<CuentaInteres> escritorCuentaInteres;
    private final ItemWriter<MovimientoAnual> escritorMovimientoAnual;
    private final ItemWriter<Object[]> escritorReporteDiario;
    private final ItemWriter<EstadoCuentaAnual> escritorInformeAuditoria;

    private final TransaccionItemProcessor procesadorTransacciones;
    private final InteresItemProcessor procesadorIntereses;
    private final MovimientoAnualItemProcessor procesadorMovimientos;

    // Conexiones a las bases de los microservicios de destino. Solo las usa el
    // ultimo paso de cada job, el que carga el resultado ya saneado.
    private final JdbcTemplate jdbcClientes;
    private final JdbcTemplate jdbcCuentas;
    private final JdbcTemplate jdbcPagos;

    @SuppressWarnings("java:S107") // La fabrica concentra a proposito las piezas de los tres jobs.
    public FabricaDeJobs(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         JdbcTemplate jdbcTemplate,
                         BancoBatchProperties propiedades,
                         LectoresConfig lectores,
                         RegistroRechazadoSkipListener registroRechazadoSkipListener,
                         JobCompletionListener jobCompletionListener,
                         MetricasStepListener metricasStepListener,
                         @Qualifier("ejecutorDeParticiones") org.springframework.core.task.TaskExecutor ejecutorDeParticiones,
                         @Qualifier("lectorTransacciones") ItemReader<TransaccionCruda> lectorTransacciones,
                         @Qualifier("lectorIntereses") ItemReader<InteresCrudo> lectorIntereses,
                         @Qualifier("lectorCuentasAnuales") ItemReader<MovimientoCrudo> lectorCuentasAnuales,
                         @Qualifier("lectorResumenDiario") ItemReader<Object[]> lectorResumenDiario,
                         @Qualifier("lectorEstadosCuenta") ItemReader<EstadoCuentaAnual> lectorEstadosCuenta,
                         @Qualifier("escritorTransacciones") ItemWriter<Transaccion> escritorTransacciones,
                         @Qualifier("escritorCuentaInteres") ItemWriter<CuentaInteres> escritorCuentaInteres,
                         @Qualifier("escritorMovimientoAnual") ItemWriter<MovimientoAnual> escritorMovimientoAnual,
                         @Qualifier("escritorReporteDiario") ItemWriter<Object[]> escritorReporteDiario,
                         @Qualifier("escritorInformeAuditoria") ItemWriter<EstadoCuentaAnual> escritorInformeAuditoria,
                         TransaccionItemProcessor procesadorTransacciones,
                         InteresItemProcessor procesadorIntereses,
                         MovimientoAnualItemProcessor procesadorMovimientos,
                         @Qualifier("jdbcClientes") JdbcTemplate jdbcClientes,
                         @Qualifier("jdbcCuentas") JdbcTemplate jdbcCuentas,
                         @Qualifier("jdbcPagos") JdbcTemplate jdbcPagos) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = jdbcTemplate;
        this.propiedades = propiedades;
        this.lectores = lectores;
        this.registroRechazadoSkipListener = registroRechazadoSkipListener;
        this.jobCompletionListener = jobCompletionListener;
        this.metricasStepListener = metricasStepListener;
        this.ejecutorDeParticiones = ejecutorDeParticiones;
        this.lectorTransacciones = lectorTransacciones;
        this.lectorIntereses = lectorIntereses;
        this.lectorCuentasAnuales = lectorCuentasAnuales;
        this.lectorResumenDiario = lectorResumenDiario;
        this.lectorEstadosCuenta = lectorEstadosCuenta;
        this.escritorTransacciones = escritorTransacciones;
        this.escritorCuentaInteres = escritorCuentaInteres;
        this.escritorMovimientoAnual = escritorMovimientoAnual;
        this.escritorReporteDiario = escritorReporteDiario;
        this.escritorInformeAuditoria = escritorInformeAuditoria;
        this.procesadorTransacciones = procesadorTransacciones;
        this.procesadorIntereses = procesadorIntereses;
        this.procesadorMovimientos = procesadorMovimientos;
        this.jdbcClientes = jdbcClientes;
        this.jdbcCuentas = jdbcCuentas;
        this.jdbcPagos = jdbcPagos;
    }

    // =================================================================
    // JOB 1 - Reporte de transacciones diarias
    // =================================================================
    public Job reporteTransaccionesDiarias(int gridSize, int chunkSize) {
        Step preparacion = pasoTasklet("preparacionTransaccionesStep",
                new LimpiezaTasklet(jdbcTemplate, JOB_TRANSACCIONES,
                        List.of("anomalia_transaccion", "resumen_transaccion_diaria", "transaccion")));

        Step trabajador = pasoTrabajador("cargaTransaccionesWorkerStep", chunkSize,
                lectorTransacciones, procesadorTransacciones, escritorTransacciones);

        Step carga = pasoParticionado(STEP_CARGA_TRANSACCIONES, "cargaTransaccionesWorkerStep",
                new RangoArchivoPartitioner(lectores.recursoTransacciones(), LectoresConfig.LINEAS_DE_CABECERA),
                trabajador, gridSize);

        Step anomalias = pasoTasklet("deteccionAnomaliasStep",
                new DeteccionAnomaliasTasklet(jdbcTemplate, propiedades.getReglas().getMontoAnomaloDesde()));

        Step resumen = pasoTasklet("resumenDiarioStep", new ResumenDiarioTasklet(jdbcTemplate));

        Step informe = pasoInforme("informeTransaccionesStep", lectorResumenDiario, escritorReporteDiario);

        // Ultimo paso: cargar lo ya saneado en la base de ms-pagos. Va DESPUES
        // del decider de calidad, de modo que un archivo con demasiada basura
        // nunca llega a tocar las bases operativas.
        Step migracion = pasoTasklet("migracionPagosStep",
                new MigracionPagosTasklet(jdbcTemplate, jdbcPagos,
                        propiedades.getMigracion().getCuentaConcentradora()));

        Step alerta = pasoTasklet("alertaCalidadTransaccionesStep",
                new AlertaCalidadTasklet(jdbcTemplate, JOB_TRANSACCIONES));

        CalidadDatosDecider decider = new CalidadDatosDecider(
                STEP_CARGA_TRANSACCIONES, propiedades.getTolerancia().getUmbralCalidad());

        return new JobBuilder(JOB_TRANSACCIONES, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobCompletionListener)
                .flow(preparacion)
                .next(carga)
                .next(decider)
                    .on(CalidadDatosDecider.CALIDAD_OK).to(anomalias)
                .from(anomalias).on("*").to(resumen)
                .from(resumen).on("*").to(informe)
                .from(informe).on("*").to(migracion)
                .from(decider).on(CalidadDatosDecider.CALIDAD_INSUFICIENTE).to(alerta)
                .from(alerta).on("*").fail()
                .end()
                .build();
    }

    // =================================================================
    // JOB 2 - Calculo de intereses mensuales
    // =================================================================
    public Job calculoInteresesMensuales(int gridSize, int chunkSize) {
        Step preparacion = pasoTasklet("preparacionInteresesStep",
                new LimpiezaTasklet(jdbcTemplate, JOB_INTERESES,
                        List.of("resumen_interes_mensual", "cuenta_interes")));

        Step trabajador = pasoTrabajador("cargaInteresesWorkerStep", chunkSize,
                lectorIntereses, procesadorIntereses, escritorCuentaInteres);

        Step carga = pasoParticionado(STEP_CARGA_INTERESES, "cargaInteresesWorkerStep",
                new RangoArchivoPartitioner(lectores.recursoIntereses(), LectoresConfig.LINEAS_DE_CABECERA),
                trabajador, gridSize);

        Step deduplicacion = pasoTasklet("deduplicacionInteresesStep",
                new DeduplicacionInteresesTasklet(jdbcTemplate, JOB_INTERESES));

        Step resumen = pasoTasklet("resumenInteresesStep", new ResumenInteresesTasklet(jdbcTemplate));

        // Ultimo paso: cargar titulares y cuentas en ms-clientes y ms-cuentas.
        // Este job es el que debe correr PRIMERO de los tres, porque los otros
        // dos referencian las cuentas que aqui se crean.
        Step migracion = pasoTasklet("migracionClientesYCuentasStep",
                new MigracionClientesYCuentasTasklet(jdbcTemplate, jdbcClientes, jdbcCuentas,
                        propiedades.getMigracion().getUmbralPreferente()));

        Step alerta = pasoTasklet("alertaCalidadInteresesStep",
                new AlertaCalidadTasklet(jdbcTemplate, JOB_INTERESES));

        CalidadDatosDecider decider = new CalidadDatosDecider(
                STEP_CARGA_INTERESES, propiedades.getTolerancia().getUmbralCalidad());

        return new JobBuilder(JOB_INTERESES, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobCompletionListener)
                .flow(preparacion)
                .next(carga)
                .next(decider)
                    .on(CalidadDatosDecider.CALIDAD_OK).to(deduplicacion)
                .from(deduplicacion).on("*").to(resumen)
                .from(resumen).on("*").to(migracion)
                .from(decider).on(CalidadDatosDecider.CALIDAD_INSUFICIENTE).to(alerta)
                .from(alerta).on("*").fail()
                .end()
                .build();
    }

    // =================================================================
    // JOB 3 - Generacion de estados de cuenta anuales
    // =================================================================
    public Job estadosCuentaAnuales(int gridSize, int chunkSize) {
        Step preparacion = pasoTasklet("preparacionEstadosCuentaStep",
                new LimpiezaTasklet(jdbcTemplate, JOB_ESTADOS_CUENTA,
                        List.of("estado_cuenta_anual", "movimiento_anual")));

        Step trabajador = pasoTrabajador("cargaMovimientosWorkerStep", chunkSize,
                lectorCuentasAnuales, procesadorMovimientos, escritorMovimientoAnual);

        Step carga = pasoParticionado(STEP_CARGA_MOVIMIENTOS, "cargaMovimientosWorkerStep",
                new RangoArchivoPartitioner(lectores.recursoCuentasAnuales(), LectoresConfig.LINEAS_DE_CABECERA),
                trabajador, gridSize);

        Step compilacion = pasoTasklet("compilacionEstadosStep", new CompilacionEstadosTasklet(jdbcTemplate));

        Step informe = pasoInforme("informeAuditoriaStep", lectorEstadosCuenta, escritorInformeAuditoria);

        // Ultimo paso: cargar el historial de movimientos en ms-cuentas. Exige
        // que el job de intereses haya corrido antes, porque un movimiento
        // referencia una cuenta que aquel es el que crea.
        Step migracion = pasoTasklet("migracionMovimientosStep",
                new MigracionMovimientosTasklet(jdbcTemplate, jdbcCuentas));

        Step alerta = pasoTasklet("alertaCalidadEstadosStep",
                new AlertaCalidadTasklet(jdbcTemplate, JOB_ESTADOS_CUENTA));

        CalidadDatosDecider decider = new CalidadDatosDecider(
                STEP_CARGA_MOVIMIENTOS, propiedades.getTolerancia().getUmbralCalidad());

        return new JobBuilder(JOB_ESTADOS_CUENTA, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobCompletionListener)
                .flow(preparacion)
                .next(carga)
                .next(decider)
                    .on(CalidadDatosDecider.CALIDAD_OK).to(compilacion)
                .from(compilacion).on("*").to(informe)
                .from(informe).on("*").to(migracion)
                .from(decider).on(CalidadDatosDecider.CALIDAD_INSUFICIENTE).to(alerta)
                .from(alerta).on("*").fail()
                .end()
                .build();
    }

    // =================================================================
    // Piezas reutilizables
    // =================================================================

    /**
     * Step manager del particionado: no procesa datos, reparte el archivo
     * y coordina a los trabajadores.
     *
     * <p>El {@link TaskExecutorPartitionHandler} se construye de forma
     * explicita (en lugar de dejar que lo derive el builder) para dejar a
     * la vista las tres piezas que gobiernan el escalado: el step que se
     * replica, el pool que lo ejecuta y el numero de particiones.</p>
     */
    private Step pasoParticionado(String nombreManager, String nombreTrabajador,
                                  Partitioner particionador, Step trabajador, int gridSize) {
        TaskExecutorPartitionHandler manejador = new TaskExecutorPartitionHandler();
        manejador.setStep(trabajador);
        manejador.setTaskExecutor(ejecutorDeParticiones);
        manejador.setGridSize(gridSize);
        try {
            manejador.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("No fue posible inicializar el manejador de particiones", e);
        }

        return new StepBuilder(nombreManager, jobRepository)
                .partitioner(nombreTrabajador, particionador)
                .partitionHandler(manejador)
                .listener(metricasStepListener)
                .build();
    }

    /**
     * Step trabajador: el que realmente lee, valida, transforma y escribe.
     * Se instancia una vez por particion y concentra toda la tolerancia a
     * fallos del proceso.
     */
    private <I, O> Step pasoTrabajador(String nombre, int chunkSize,
                                       ItemReader<I> lector,
                                       ItemProcessor<I, O> procesador,
                                       ItemWriter<O> escritor) {

        BancoBatchProperties.Tolerancia tolerancia = propiedades.getTolerancia();

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(tolerancia.getBackoffInicialMs());
        backOff.setMultiplier(tolerancia.getBackoffMultiplicador());
        backOff.setMaxInterval(tolerancia.getBackoffMaximoMs());

        // Proxy de ambito step: se resuelve a una instancia distinta en cada
        // particion, con su propio contador y su propio archivo de errores.
        RegistroRechazadoSkipListener cuarentena = registroRechazadoSkipListener;

        return new StepBuilder(nombre, jobRepository)
                .<I, O>chunk(chunkSize, transactionManager)
                .reader(lector)
                .processor(procesador)
                .writer(escritor)
                .faultTolerant()
                .skipPolicy(new PoliticaOmisionPersonalizada(tolerancia.getSkipLimit()))
                .retryPolicy(new PoliticaReintentoPersonalizada(tolerancia.getRetryLimit()))
                .backOffPolicy(backOff)
                // Los procesadores son funciones puras, asi que su salida se
                // puede cachear entre reintentos de un chunk revertido en vez
                // de recalcularla item por item en cada rescaneo.
                .processorNonTransactional()
                .listener((SkipListener<? super I, ? super O>) cuarentena)
                .listener((StepExecutionListener) cuarentena)
                .listener(metricasStepListener)
                .build();
    }

    /** Step de informe: recorre la tabla consolidada y la vuelca a archivo. */
    private <T> Step pasoInforme(String nombre, ItemReader<T> lector, ItemWriter<T> escritor) {
        return new StepBuilder(nombre, jobRepository)
                .<T, T>chunk(100, transactionManager)
                .reader(lector)
                .writer(escritor)
                .listener(metricasStepListener)
                .build();
    }

    private Step pasoTasklet(String nombre, Tasklet tarea) {
        return new StepBuilder(nombre, jobRepository)
                .tasklet(tarea, transactionManager)
                .listener(metricasStepListener)
                .build();
    }
}
