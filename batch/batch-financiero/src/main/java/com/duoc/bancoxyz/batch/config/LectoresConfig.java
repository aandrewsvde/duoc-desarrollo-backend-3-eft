package com.duoc.bancoxyz.batch.config;

import com.duoc.bancoxyz.batch.domain.InteresCrudo;
import com.duoc.bancoxyz.batch.domain.MovimientoCrudo;
import com.duoc.bancoxyz.batch.domain.TransaccionCruda;
import com.duoc.bancoxyz.batch.partition.RangoArchivoPartitioner;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Lectores de los archivos legacy, uno por proceso.
 *
 * <p>Todos son {@link StepScope}: en un step particionado eso significa
 * <b>una instancia de lector por particion</b>, cada una con su propio
 * estado de posicion en el archivo. Es lo que permite que varias
 * particiones recorran el mismo CSV en paralelo sin pisarse.</p>
 *
 * <p>Cada lector se acota al tramo que le asigno el
 * {@link RangoArchivoPartitioner}: salta la cabecera mas los registros
 * anteriores a su rango ({@code linesToSkip}) y lee exactamente los de su
 * cuota ({@code maxItemCount}). Fuera de un step particionado los valores
 * por defecto hacen que se lea el archivo completo.</p>
 *
 * <p>Los campos numericos se tipan aqui, en la lectura. Es deliberado: una
 * fila cuyo campo obligatorio no es convertible es un problema de
 * <i>formato</i> del archivo, no de reglas de negocio, y debe fallar como
 * {@code FlatFileParseException} en la fase de LECTURA para quedar
 * clasificada como tal en la cuarentena.</p>
 */
@Configuration
public class LectoresConfig {

    private static final Logger log = LoggerFactory.getLogger(LectoresConfig.class);

    /** El CSV trae una unica linea de cabecera. */
    public static final int LINEAS_DE_CABECERA = 1;

    private final BancoBatchProperties propiedades;
    private final ResourceLoader resourceLoader;

    public LectoresConfig(BancoBatchProperties propiedades, ResourceLoader resourceLoader) {
        this.propiedades = propiedades;
        this.resourceLoader = resourceLoader;
    }

    public Resource recursoTransacciones() {
        return resourceLoader.getResource(propiedades.getEntrada().getTransacciones());
    }

    public Resource recursoIntereses() {
        return resourceLoader.getResource(propiedades.getEntrada().getIntereses());
    }

    public Resource recursoCuentasAnuales() {
        return resourceLoader.getResource(propiedades.getEntrada().getCuentasAnuales());
    }

    // -----------------------------------------------------------------
    // Job 1 - movimientos_financieros_diarios.csv
    // -----------------------------------------------------------------
    @Bean
    @StepScope
    public FlatFileItemReader<TransaccionCruda> lectorTransacciones(
            @Value("#{stepExecutionContext['inicio'] ?: 0}") int inicio,
            @Value("#{stepExecutionContext['cantidad'] ?: T(java.lang.Integer).MAX_VALUE}") int cantidad) {

        log.debug("Lector de transacciones para el rango [{}, {})", inicio, inicio + cantidad);

        return new FlatFileItemReaderBuilder<TransaccionCruda>()
                .name("lectorTransacciones")
                .resource(recursoTransacciones())
                .encoding("UTF-8")
                .linesToSkip(LINEAS_DE_CABECERA + inicio)
                .maxItemCount(cantidad)
                .delimited()
                .delimiter(",")
                .names("id", "fecha", "monto", "tipo")
                .fieldSetMapper(fs -> new TransaccionCruda(
                        fs.readLong("id"),
                        fs.readString("fecha"),
                        decimalOpcional(fs, "monto"),
                        fs.readString("tipo"),
                        lineaOrigen(fs)))
                .build();
    }

    // -----------------------------------------------------------------
    // Job 2 - intereses_trimestrales.csv
    // -----------------------------------------------------------------
    @Bean
    @StepScope
    public FlatFileItemReader<InteresCrudo> lectorIntereses(
            @Value("#{stepExecutionContext['inicio'] ?: 0}") int inicio,
            @Value("#{stepExecutionContext['cantidad'] ?: T(java.lang.Integer).MAX_VALUE}") int cantidad) {

        return new FlatFileItemReaderBuilder<InteresCrudo>()
                .name("lectorIntereses")
                .resource(recursoIntereses())
                .encoding("UTF-8")
                .linesToSkip(LINEAS_DE_CABECERA + inicio)
                .maxItemCount(cantidad)
                .delimited()
                .delimiter(",")
                .names("cuenta_id", "nombre", "saldo", "edad", "tipo")
                .fieldSetMapper(fs -> new InteresCrudo(
                        fs.readInt("cuenta_id"),
                        fs.readString("nombre"),
                        decimalOpcional(fs, "saldo"),
                        // La edad es obligatoria y tipada: si viene vacia o no
                        // numerica la fila se rechaza ya en la lectura.
                        fs.readInt("edad"),
                        fs.readString("tipo"),
                        lineaOrigen(fs)))
                .build();
    }

    // -----------------------------------------------------------------
    // Job 3 - estados_financieros_anuales.csv
    // -----------------------------------------------------------------
    @Bean
    @StepScope
    public FlatFileItemReader<MovimientoCrudo> lectorCuentasAnuales(
            @Value("#{stepExecutionContext['inicio'] ?: 0}") int inicio,
            @Value("#{stepExecutionContext['cantidad'] ?: T(java.lang.Integer).MAX_VALUE}") int cantidad) {

        return new FlatFileItemReaderBuilder<MovimientoCrudo>()
                .name("lectorCuentasAnuales")
                .resource(recursoCuentasAnuales())
                .encoding("UTF-8")
                .linesToSkip(LINEAS_DE_CABECERA + inicio)
                .maxItemCount(cantidad)
                .delimited()
                .delimiter(",")
                .names("cuenta_id", "fecha", "transaccion", "monto", "descripcion")
                .fieldSetMapper(fs -> new MovimientoCrudo(
                        fs.readInt("cuenta_id"),
                        fs.readString("fecha"),
                        fs.readString("transaccion"),
                        decimalOpcional(fs, "monto"),
                        fs.readString("descripcion"),
                        lineaOrigen(fs)))
                .build();
    }

    // -----------------------------------------------------------------
    // Utilidades de mapeo
    // -----------------------------------------------------------------

    /**
     * Campo numerico opcional: vacio se mapea a {@code null} para que la
     * regla de negocio decida y explique el rechazo; un texto no numerico
     * si es un error de formato y propaga {@link NumberFormatException}.
     */
    private static BigDecimal decimalOpcional(FieldSet fs, String campo) {
        String valor = fs.readString(campo);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return new BigDecimal(valor.trim());
    }

    /** Reconstruye la fila original para poder citarla en la cuarentena. */
    private static String lineaOrigen(FieldSet fs) {
        return String.join(",", fs.getValues());
    }
}
