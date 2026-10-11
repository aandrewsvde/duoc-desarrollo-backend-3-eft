package com.duoc.bancoxyz.batch.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normaliza a ISO-8601 las fechas del sistema legacy, que llegan en cuatro
 * formatos mezclados dentro del mismo archivo.
 *
 * <p>Se usa {@link ResolverStyle#STRICT} de forma deliberada: es lo que
 * permite rechazar fechas sintacticamente bien formadas pero imposibles
 * (por ejemplo {@code 2024-13-01}, presente en {@code movimientos_financieros_diarios.csv}),
 * que un parseo permisivo aceptaria desplazando el mes.</p>
 *
 * <p>Los formatos con dia y mes ambiguos se interpretan como
 * <b>dia-mes-anio</b>, criterio consistente con el resto del dataset: hay
 * valores como {@code 24/07/2024} y {@code 30-12-2024} cuyo primer campo
 * supera 12 y solo admiten esa lectura.</p>
 */
public final class NormalizadorFechas {

    /** Resultado del parseo: la fecha normalizada y el formato de origen detectado. */
    public record FechaNormalizada(LocalDate fecha, String formatoDetectado) {
    }

    private static final Map<String, DateTimeFormatter> FORMATOS = new LinkedHashMap<>();

    static {
        registrar("yyyy-MM-dd", "uuuu-MM-dd");
        registrar("dd-MM-yyyy", "dd-MM-uuuu");
        registrar("dd/MM/yyyy", "dd/MM/uuuu");
        registrar("yyyy/MM/dd", "uuuu/MM/dd");
    }

    private NormalizadorFechas() {
    }

    private static void registrar(String etiqueta, String patron) {
        FORMATOS.put(etiqueta, DateTimeFormatter.ofPattern(patron).withResolverStyle(ResolverStyle.STRICT));
    }

    /**
     * @return la fecha normalizada, o {@code null} si el valor no calza con
     *         ninguno de los formatos conocidos o representa un dia inexistente.
     */
    public static FechaNormalizada normalizar(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String limpio = valor.trim();
        for (Map.Entry<String, DateTimeFormatter> formato : FORMATOS.entrySet()) {
            try {
                return new FechaNormalizada(LocalDate.parse(limpio, formato.getValue()), formato.getKey());
            } catch (DateTimeParseException ignorada) {
                // Se prueba el siguiente formato del catalogo.
            }
        }
        return null;
    }

    /** Formatos aceptados, para documentar el rechazo en la cuarentena. */
    public static String formatosAceptados() {
        return String.join(" | ", FORMATOS.keySet());
    }
}
