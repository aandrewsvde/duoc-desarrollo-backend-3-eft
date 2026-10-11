package com.duoc.bancoxyz.batch.support;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de normalizacion de texto para datos legacy. */
public final class Textos {

    private Textos() {
    }

    /**
     * Deja el valor en minusculas, sin espacios sobrantes y sin acentos.
     * Es lo que permite reconciliar variantes del mismo concepto que el
     * sistema antiguo grabo de forma inconsistente ({@code deposito} y
     * {@code deposito} con tilde conviven en {@code estados_financieros_anuales.csv}).
     */
    public static String canonico(String valor) {
        if (valor == null) {
            return "";
        }
        String sinAcentos = Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return sinAcentos.toLowerCase(Locale.ROOT);
    }

    /** Recorta a la longitud maxima admitida por la columna destino. */
    public static String acotar(String valor, int maximo) {
        if (valor == null) {
            return "";
        }
        return valor.length() <= maximo ? valor : valor.substring(0, maximo);
    }
}
