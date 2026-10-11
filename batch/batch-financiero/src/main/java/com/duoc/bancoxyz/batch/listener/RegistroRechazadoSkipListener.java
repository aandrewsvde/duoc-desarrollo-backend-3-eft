package com.duoc.bancoxyz.batch.listener;

import com.duoc.bancoxyz.batch.domain.DatoInvalidoException;
import com.duoc.bancoxyz.batch.writer.CuarentenaDao;
import com.duoc.bancoxyz.batch.writer.RegistroRechazado;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;

/**
 * Deriva a cuarentena todo registro omitido, en dos destinos
 * complementarios:
 *
 * <ul>
 *   <li>la tabla {@code registro_rechazado}, para poder auditar y cuadrar
 *       con SQL que <i>validos + rechazados = registros de entrada</i>;</li>
 *   <li>un CSV de errores por particion, que es la evidencia operativa que
 *       se entrega al equipo de datos del banco.</li>
 * </ul>
 *
 * <p>Se crea un archivo por ejecucion de step: como el step de carga esta
 * particionado, cada particion escribe el suyo y no hay contencion entre
 * hilos sobre un archivo compartido. El archivo se abre de forma perezosa,
 * de modo que una particion sin errores no deja un CSV vacio.</p>
 */
public class RegistroRechazadoSkipListener implements SkipListener<Object, Object>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(RegistroRechazadoSkipListener.class);
    private static final String CABECERA = "fase,motivo,excepcion,hilo,linea_origen";

    private final CuarentenaDao cuarentenaDao;
    private final String directorioSalida;

    /**
     * Tamano del lote de cuarentena. Acota cuanto se puede perder si el
     * proceso muere de forma abrupta, sin volver a una transaccion por
     * registro.
     */
    private static final int TAMANO_LOTE = 200;

    private final AtomicInteger omitidos = new AtomicInteger();
    private final Object candado = new Object();
    private final List<RegistroRechazado> pendientes = new ArrayList<>();

    private volatile StepExecution stepExecution;
    private volatile BufferedWriter escritor;
    private volatile Path archivo;

    public RegistroRechazadoSkipListener(CuarentenaDao cuarentenaDao, String directorioSalida) {
        this.cuarentenaDao = cuarentenaDao;
        this.directorioSalida = directorioSalida;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.omitidos.set(0);
        synchronized (candado) {
            pendientes.clear();
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        vaciarPendientes();
        cerrarArchivo();
        if (omitidos.get() > 0) {
            log.info("[{}] {} registros derivados a cuarentena. Detalle en {}",
                    stepExecution.getStepName(), omitidos.get(), archivo);
        }
        return stepExecution.getExitStatus();
    }

    @Override
    public void onSkipInRead(Throwable excepcion) {
        String linea = "";
        String motivo = excepcion.getMessage();
        if (excepcion instanceof FlatFileParseException parseo) {
            linea = parseo.getInput();
            motivo = "Linea ilegible en el archivo de origen (linea " + parseo.getLineNumber() + "): "
                    + raizDe(parseo).getMessage();
        }
        registrar("LECTURA", linea, motivo, excepcion);
    }

    @Override
    public void onSkipInProcess(Object item, Throwable excepcion) {
        String linea = excepcion instanceof DatoInvalidoException dato ? dato.getLineaOrigen() : String.valueOf(item);
        registrar("PROCESO", linea, excepcion.getMessage(), excepcion);
    }

    @Override
    public void onSkipInWrite(Object item, Throwable excepcion) {
        registrar("ESCRITURA", String.valueOf(item), excepcion.getMessage(), excepcion);
    }

    private void registrar(String fase, String lineaOrigen, String motivo, Throwable excepcion) {
        omitidos.incrementAndGet();
        String excepcionNombre = excepcion.getClass().getSimpleName();
        String motivoSeguro = motivo == null ? excepcionNombre : motivo;

        encolar(new RegistroRechazado(
                stepExecution.getJobExecutionId(),
                stepExecution.getJobExecution().getJobInstance().getJobName(),
                stepExecution.getStepName(),
                fase,
                lineaOrigen,
                motivoSeguro,
                excepcionNombre,
                Thread.currentThread().getName()));

        escribirEnCsv(fase, motivoSeguro, excepcionNombre, lineaOrigen);
    }

    private void encolar(RegistroRechazado registro) {
        List<RegistroRechazado> aPersistir = null;
        synchronized (candado) {
            pendientes.add(registro);
            if (pendientes.size() >= TAMANO_LOTE) {
                aPersistir = List.copyOf(pendientes);
                pendientes.clear();
            }
        }
        if (aPersistir != null) {
            cuarentenaDao.registrarLote(aPersistir);
        }
    }

    private void vaciarPendientes() {
        List<RegistroRechazado> aPersistir;
        synchronized (candado) {
            if (pendientes.isEmpty()) {
                return;
            }
            aPersistir = List.copyOf(pendientes);
            pendientes.clear();
        }
        cuarentenaDao.registrarLote(aPersistir);
    }

    private void escribirEnCsv(String fase, String motivo, String excepcion, String lineaOrigen) {
        synchronized (candado) {
            try {
                if (escritor == null) {
                    Path directorio = Paths.get(directorioSalida, "errores");
                    Files.createDirectories(directorio);
                    String nombre = "errores_%s_%d_%s.csv".formatted(
                            stepExecution.getJobExecution().getJobInstance().getJobName(),
                            stepExecution.getJobExecutionId(),
                            stepExecution.getStepName().replace(':', '_'));
                    archivo = directorio.resolve(nombre);
                    escritor = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8);
                    escritor.write(CABECERA);
                    escritor.newLine();
                }
                escritor.write(String.join(",",
                        entrecomillar(fase),
                        entrecomillar(motivo),
                        entrecomillar(excepcion),
                        entrecomillar(Thread.currentThread().getName()),
                        entrecomillar(lineaOrigen)));
                escritor.newLine();
            } catch (IOException e) {
                throw new UncheckedIOException("No fue posible escribir el CSV de errores", e);
            }
        }
    }

    private void cerrarArchivo() {
        synchronized (candado) {
            if (escritor != null) {
                try {
                    escritor.flush();
                    escritor.close();
                } catch (IOException e) {
                    log.warn("No fue posible cerrar el CSV de errores {}", archivo, e);
                } finally {
                    escritor = null;
                }
            }
        }
    }

    private static String entrecomillar(String valor) {
        String limpio = valor == null ? "" : valor.replace("\"", "'").replace("\n", " ").replace("\r", " ");
        return "\"" + limpio + "\"";
    }

    private static Throwable raizDe(Throwable excepcion) {
        Throwable actual = excepcion;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual;
    }
}
