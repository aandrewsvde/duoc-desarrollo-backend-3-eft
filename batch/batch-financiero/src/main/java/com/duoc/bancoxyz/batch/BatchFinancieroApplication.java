package com.duoc.bancoxyz.batch;

import com.duoc.bancoxyz.batch.config.BancoBatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Migracion de los procesos batch legacy del Banco XYZ a Spring Batch.
 *
 * <p>Reemplaza tres rutinas del sistema antiguo (COBOL/shell) por jobs
 * de Spring Batch particionados y tolerantes a fallos:</p>
 * <ol>
 *   <li>{@code reporteTransaccionesDiariasJob}</li>
 *   <li>{@code calculoInteresesMensualesJob}</li>
 *   <li>{@code estadosCuentaAnualesJob}</li>
 * </ol>
 *
 * <p>Ninguno se lanza al arrancar. La via normal en este sistema es el
 * endpoint REST ({@code POST /api/batch/migracion-completa}); los argumentos
 * {@code --job=<nombre>} y {@code --benchmark} se conservan para poder
 * ejecutarlo a mano dentro del contenedor.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(BancoBatchProperties.class)
public class BatchFinancieroApplication {

    public static void main(String[] args) {
        var contexto = SpringApplication.run(BatchFinancieroApplication.class, args);

        // El modulo tiene dos modos de uso y cada uno necesita lo contrario.
        //
        // Con --job o --benchmark se comporta como un proceso de un solo uso:
        // ejecuta, devuelve el codigo de salida que corresponde y termina. Es
        // lo que espera un planificador que lo invoca y mira ese codigo.
        //
        // Sin argumentos queda ESCUCHANDO, porque en este sistema es un
        // contenedor que arranca con los demas y espera a que lo disparen por
        // REST. Llamar a System.exit tambien aqui -que es lo que hacia cuando
        // era un proceso suelto- apagaria el contenedor nada mas levantarlo.
        boolean unSoloUso = java.util.Arrays.stream(args)
                .anyMatch(a -> a.startsWith("--job") || a.startsWith("--benchmark"));

        if (unSoloUso) {
            System.exit(SpringApplication.exit(contexto));
        }
    }
}
