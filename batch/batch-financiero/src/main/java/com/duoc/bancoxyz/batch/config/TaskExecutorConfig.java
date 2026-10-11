package com.duoc.bancoxyz.batch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool de hilos que ejecuta las particiones.
 *
 * <p>Es el recurso que hace efectivo el paralelismo: el
 * {@code TaskExecutorPartitionHandler} entrega aqui una tarea por
 * particion y este pool decide cuantas corren a la vez.</p>
 *
 * <p>Dimensionamiento (justificado en el README):</p>
 * <ul>
 *   <li>{@code corePoolSize} debe ser al menos el mayor grid que se vaya a
 *       comparar. Si queda por debajo, las particiones sobrantes esperan en
 *       la cola y la medicion del escalado mide la cola, no el paralelismo.</li>
 *   <li>{@code maxPoolSize} da holgura para picos, sin llegar a superar el
 *       numero de nucleos disponibles, punto a partir del cual mas hilos
 *       solo agregan cambios de contexto.</li>
 *   <li>El prefijo de nombre hace que cada particion sea identificable en
 *       el log, que es como se evidencia la ejecucion concurrente.</li>
 * </ul>
 */
@Configuration
public class TaskExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutorConfig.class);

    @Bean(name = "ejecutorDeParticiones", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor ejecutorDeParticiones(BancoBatchProperties propiedades) {
        BancoBatchProperties.Particion particion = propiedades.getParticion();

        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(particion.getCorePoolSize());
        ejecutor.setMaxPoolSize(particion.getMaxPoolSize());
        ejecutor.setQueueCapacity(particion.getQueueCapacity());
        ejecutor.setThreadNamePrefix(particion.getThreadNamePrefix());
        ejecutor.setWaitForTasksToCompleteOnShutdown(true);
        ejecutor.initialize();

        log.info("Pool de particiones inicializado: core={} max={} cola={} prefijo='{}' (nucleos disponibles={})",
                particion.getCorePoolSize(), particion.getMaxPoolSize(), particion.getQueueCapacity(),
                particion.getThreadNamePrefix(), Runtime.getRuntime().availableProcessors());
        return ejecutor;
    }
}
