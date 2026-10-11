package com.duoc.bancoxyz.batch.config;

import com.duoc.bancoxyz.batch.listener.RegistroRechazadoSkipListener;
import com.duoc.bancoxyz.batch.writer.CuarentenaDao;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Listeners que necesitan una instancia por ejecucion de step.
 *
 * <p>El listener de cuarentena <b>debe</b> ser {@link StepScope}. Un step
 * particionado reutiliza el mismo objeto {@code Step} en todos los hilos
 * trabajadores: si el listener fuera un singleton, las cuatro particiones
 * compartirian el mismo {@code StepExecution} y el mismo archivo de
 * errores, y cada una sobrescribiria el estado de las otras. Con ambito de
 * step, cada particion recibe su propia instancia, su propio contador y su
 * propio CSV de errores.</p>
 */
@Configuration
public class ListenersConfig {

    @Bean
    @StepScope
    public RegistroRechazadoSkipListener registroRechazadoSkipListener(
            CuarentenaDao cuarentenaDao, BancoBatchProperties propiedades) {
        return new RegistroRechazadoSkipListener(cuarentenaDao, propiedades.getSalida().getDirectorio());
    }
}
