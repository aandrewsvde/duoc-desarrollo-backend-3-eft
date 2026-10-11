package com.duoc.bancoxyz.batch.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;

/**
 * Cuenta los registros utiles de un CSV (excluyendo cabecera y lineas en
 * blanco). El particionador necesita el total para repartir rangos
 * disjuntos entre las particiones.
 */
public final class ContadorRegistros {

    private ContadorRegistros() {
    }

    public static int contar(Resource recurso, int lineasDeCabecera) {
        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {
            int total = 0;
            String linea;
            while ((linea = lector.readLine()) != null) {
                if (!linea.isBlank()) {
                    total++;
                }
            }
            return Math.max(0, total - lineasDeCabecera);
        } catch (IOException e) {
            throw new UncheckedIOException("No fue posible contar los registros de " + recurso, e);
        }
    }
}
