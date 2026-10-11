package com.duoc.bancoxyz.batch.partition;

import com.duoc.bancoxyz.batch.support.ContadorRegistros;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.Resource;

/**
 * Particionador por rango de registros: divide el archivo de entrada en
 * {@code gridSize} tramos disjuntos y contiguos, y publica en el
 * {@link ExecutionContext} de cada particion el desplazamiento inicial y la
 * cantidad de registros que le corresponden.
 *
 * <p>El reparto es balanceado: cuando el total no es multiplo del grid, el
 * resto se distribuye de a un registro entre las primeras particiones, de
 * modo que ninguna quede con mas de un registro de diferencia respecto de
 * las demas. Esto evita que una particion rezagada marque el tiempo total
 * del step (efecto "cola larga").</p>
 *
 * <p>Cada rango es independiente: un fallo dentro de una particion se
 * aisla en esa particion y no invalida el trabajo ya confirmado por las
 * demas.</p>
 */
public class RangoArchivoPartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(RangoArchivoPartitioner.class);

    public static final String CLAVE_INICIO = "inicio";
    public static final String CLAVE_CANTIDAD = "cantidad";
    public static final String CLAVE_NOMBRE = "nombreParticion";
    public static final String CLAVE_TOTAL = "totalRegistros";

    private final Resource recurso;
    private final int lineasDeCabecera;

    public RangoArchivoPartitioner(Resource recurso, int lineasDeCabecera) {
        this.recurso = recurso;
        this.lineasDeCabecera = lineasDeCabecera;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int total = ContadorRegistros.contar(recurso, lineasDeCabecera);
        int particiones = Math.max(1, Math.min(gridSize, Math.max(1, total)));

        int base = total / particiones;
        int resto = total % particiones;

        Map<String, ExecutionContext> resultado = new HashMap<>();
        int inicio = 0;
        for (int i = 0; i < particiones; i++) {
            int cantidad = base + (i < resto ? 1 : 0);
            String nombre = "particion" + i;

            ExecutionContext contexto = new ExecutionContext();
            contexto.putInt(CLAVE_INICIO, inicio);
            contexto.putInt(CLAVE_CANTIDAD, cantidad);
            contexto.putInt(CLAVE_TOTAL, total);
            contexto.putString(CLAVE_NOMBRE, nombre);
            resultado.put(nombre, contexto);

            log.info("Particion {} -> registros [{}, {}) ({} registros)",
                    nombre, inicio, inicio + cantidad, cantidad);
            inicio += cantidad;
        }

        log.info("Archivo {}: {} registros repartidos en {} particiones (gridSize solicitado = {})",
                recurso.getFilename(), total, particiones, gridSize);
        return resultado;
    }
}
