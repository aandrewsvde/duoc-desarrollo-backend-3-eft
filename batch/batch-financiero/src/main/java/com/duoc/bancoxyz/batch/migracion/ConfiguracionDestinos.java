package com.duoc.bancoxyz.batch.migracion;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conexiones a las bases de los tres microservicios de destino.
 *
 * <p>Este es el unico componente del sistema con credenciales de las tres
 * bases a la vez, y conviene ser explicito sobre por que no contradice la regla
 * de "una base por servicio": una migracion es precisamente la operacion que
 * puebla esas bases ANTES de que el sistema entre en servicio. Lo que no
 * ocurre nunca es que el batch escriba en ellas mientras los microservicios
 * estan atendiendo trafico.</p>
 *
 * <p>Los pools son deliberadamente pequenos. El batch abre estas conexiones
 * durante segundos al final de cada job, no durante toda su vida; reservar
 * veinte conexiones por base dejaria sin margen a los propios microservicios
 * si la migracion corriera con el sistema ya levantado.</p>
 */
@Configuration
public class ConfiguracionDestinos {

    private static final int MAXIMO_CONEXIONES = 4;

    private DataSource construir(String url, String usuario, String clave, String nombrePool) {
        HikariDataSource fuente = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(usuario)
                .password(clave)
                .build();
        fuente.setMaximumPoolSize(MAXIMO_CONEXIONES);
        fuente.setPoolName(nombrePool);
        return fuente;
    }

    /**
     * La conexion a la base PROPIA del batch.
     *
     * <p>Hay que declararla explicitamente. Spring Boot crea una JdbcTemplate
     * por si solo, pero solo si no existe ninguna otra; al aparecer las tres de
     * destino se retira, y entonces todo el codigo que inyecta una JdbcTemplate
     * sin calificar deja de arrancar con un "expected single matching bean but
     * found 3". Marcarla como primaria devuelve el comportamiento anterior sin
     * tocar ninguna de las clases que ya la usaban.</p>
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource fuentePrincipal) {
        return new JdbcTemplate(fuentePrincipal);
    }

    @Bean
    public JdbcTemplate jdbcClientes(
            @Value("${banco.migracion.destino.clientes-url}") String url,
            @Value("${spring.datasource.username}") String usuario,
            @Value("${spring.datasource.password}") String clave) {
        return new JdbcTemplate(construir(url, usuario, clave, "migracion-clientes"));
    }

    @Bean
    public JdbcTemplate jdbcCuentas(
            @Value("${banco.migracion.destino.cuentas-url}") String url,
            @Value("${spring.datasource.username}") String usuario,
            @Value("${spring.datasource.password}") String clave) {
        return new JdbcTemplate(construir(url, usuario, clave, "migracion-cuentas"));
    }

    @Bean
    public JdbcTemplate jdbcPagos(
            @Value("${banco.migracion.destino.pagos-url}") String url,
            @Value("${spring.datasource.username}") String usuario,
            @Value("${spring.datasource.password}") String clave) {
        return new JdbcTemplate(construir(url, usuario, clave, "migracion-pagos"));
    }
}
