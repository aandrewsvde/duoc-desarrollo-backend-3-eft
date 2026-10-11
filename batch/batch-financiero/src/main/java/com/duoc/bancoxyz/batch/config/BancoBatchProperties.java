package com.duoc.bancoxyz.batch.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametros externalizados del batch. Todo lo que la pauta pide "decidir"
 * (cantidad de particiones, tamano de chunk, limites de omision y
 * reintento, tasas, reglas de validacion) vive aqui y no en el codigo, de
 * modo que se pueda ajustar sin recompilar y comparar configuraciones.
 */
@ConfigurationProperties(prefix = "banco")
public class BancoBatchProperties {

    private final Entrada entrada = new Entrada();
    private final Salida salida = new Salida();
    private final Particion particion = new Particion();
    private final Tolerancia tolerancia = new Tolerancia();
    private final Reglas reglas = new Reglas();
    private final Tasas tasas = new Tasas();
    private final Benchmark benchmark = new Benchmark();
    private final Migracion migracion = new Migracion();

    public Entrada getEntrada() { return entrada; }
    public Salida getSalida() { return salida; }
    public Particion getParticion() { return particion; }
    public Tolerancia getTolerancia() { return tolerancia; }
    public Reglas getReglas() { return reglas; }
    public Tasas getTasas() { return tasas; }
    public Benchmark getBenchmark() { return benchmark; }
    public Migracion getMigracion() { return migracion; }

    public static class Entrada {
        private String transacciones;
        private String intereses;
        private String cuentasAnuales;

        public String getTransacciones() { return transacciones; }
        public void setTransacciones(String v) { this.transacciones = v; }
        public String getIntereses() { return intereses; }
        public void setIntereses(String v) { this.intereses = v; }
        public String getCuentasAnuales() { return cuentasAnuales; }
        public void setCuentasAnuales(String v) { this.cuentasAnuales = v; }
    }

    /**
     * Carga del resultado saneado en las bases de los microservicios.
     *
     * <p>Las URL de destino no estan aqui sino en el archivo de configuracion
     * que entrega el config-server: son credenciales de infraestructura, no
     * reglas de negocio, y cambian de un entorno a otro.</p>
     */
    public static class Migracion {
        /**
         * Cuenta a la que se imputan las transacciones diarias del legado.
         *
         * <p>El archivo de movimientos diarios no dice a que cuenta pertenece
         * cada transaccion: solo trae un identificador correlativo. Imputarlas
         * todas a una cuenta concentradora es el supuesto que permite migrarlas
         * sin inventar una relacion que el dato no tiene.</p>
         */
        private int cuentaConcentradora = 101;

        /** Mismo umbral que aplica ms-clientes, para que el segmento coincida. */
        private java.math.BigDecimal umbralPreferente = new java.math.BigDecimal("50000");

        public int getCuentaConcentradora() { return cuentaConcentradora; }
        public void setCuentaConcentradora(int v) { this.cuentaConcentradora = v; }
        public java.math.BigDecimal getUmbralPreferente() { return umbralPreferente; }
        public void setUmbralPreferente(java.math.BigDecimal v) { this.umbralPreferente = v; }
    }

    public static class Salida {
        private String directorio = "salida";
        public String getDirectorio() { return directorio; }
        public void setDirectorio(String v) { this.directorio = v; }
    }

    public static class Particion {
        private int gridSize = 4;
        private int chunkSize = 100;
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 32;
        private String threadNamePrefix = "particion-";

        public int getGridSize() { return gridSize; }
        public void setGridSize(int v) { this.gridSize = v; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int v) { this.chunkSize = v; }
        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int v) { this.corePoolSize = v; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int v) { this.maxPoolSize = v; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
        public String getThreadNamePrefix() { return threadNamePrefix; }
        public void setThreadNamePrefix(String v) { this.threadNamePrefix = v; }
    }

    public static class Tolerancia {
        private int skipLimit = 800;
        private int retryLimit = 3;
        private long backoffInicialMs = 200;
        private double backoffMultiplicador = 2.0;
        private long backoffMaximoMs = 3000;
        private double umbralCalidad = 0.30;

        public int getSkipLimit() { return skipLimit; }
        public void setSkipLimit(int v) { this.skipLimit = v; }
        public int getRetryLimit() { return retryLimit; }
        public void setRetryLimit(int v) { this.retryLimit = v; }
        public long getBackoffInicialMs() { return backoffInicialMs; }
        public void setBackoffInicialMs(long v) { this.backoffInicialMs = v; }
        public double getBackoffMultiplicador() { return backoffMultiplicador; }
        public void setBackoffMultiplicador(double v) { this.backoffMultiplicador = v; }
        public long getBackoffMaximoMs() { return backoffMaximoMs; }
        public void setBackoffMaximoMs(long v) { this.backoffMaximoMs = v; }
        public double getUmbralCalidad() { return umbralCalidad; }
        public void setUmbralCalidad(double v) { this.umbralCalidad = v; }
    }

    public static class Reglas {
        private List<String> tiposTransaccionValidos = List.of("credito", "debito");
        private List<String> tiposCuentaValidos = List.of("ahorro", "prestamo", "hipoteca");
        private List<String> tiposMovimientoValidos = List.of("deposito", "retiro", "compra", "pago");
        private List<String> nombresInvalidos = List.of("unknown");
        private int edadMinima = 18;
        private int edadMaxima = 100;
        private int anioEsperado = 2024;
        private BigDecimal montoAnomaloDesde = new BigDecimal("3000");
        private String descripcionPorDefecto = "SIN DESCRIPCION";

        public List<String> getTiposTransaccionValidos() { return tiposTransaccionValidos; }
        public void setTiposTransaccionValidos(List<String> v) { this.tiposTransaccionValidos = normalizar(v); }
        public List<String> getTiposCuentaValidos() { return tiposCuentaValidos; }
        public void setTiposCuentaValidos(List<String> v) { this.tiposCuentaValidos = normalizar(v); }
        public List<String> getTiposMovimientoValidos() { return tiposMovimientoValidos; }
        public void setTiposMovimientoValidos(List<String> v) { this.tiposMovimientoValidos = normalizar(v); }
        public List<String> getNombresInvalidos() { return nombresInvalidos; }
        public void setNombresInvalidos(List<String> v) { this.nombresInvalidos = normalizar(v); }
        public int getEdadMinima() { return edadMinima; }
        public void setEdadMinima(int v) { this.edadMinima = v; }
        public int getEdadMaxima() { return edadMaxima; }
        public void setEdadMaxima(int v) { this.edadMaxima = v; }
        public int getAnioEsperado() { return anioEsperado; }
        public void setAnioEsperado(int v) { this.anioEsperado = v; }
        public BigDecimal getMontoAnomaloDesde() { return montoAnomaloDesde; }
        public void setMontoAnomaloDesde(BigDecimal v) { this.montoAnomaloDesde = v; }
        public String getDescripcionPorDefecto() { return descripcionPorDefecto; }
        public void setDescripcionPorDefecto(String v) { this.descripcionPorDefecto = v; }

        private static List<String> normalizar(List<String> valores) {
            return valores.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        }
    }

    public static class Tasas {
        private String periodo = "2024-12";
        private BigDecimal ahorro = new BigDecimal("0.003");
        private BigDecimal prestamo = new BigDecimal("0.015");
        private BigDecimal hipoteca = new BigDecimal("0.009");

        public String getPeriodo() { return periodo; }
        public void setPeriodo(String v) { this.periodo = v; }
        public BigDecimal getAhorro() { return ahorro; }
        public void setAhorro(BigDecimal v) { this.ahorro = v; }
        public BigDecimal getPrestamo() { return prestamo; }
        public void setPrestamo(BigDecimal v) { this.prestamo = v; }
        public BigDecimal getHipoteca() { return hipoteca; }
        public void setHipoteca(BigDecimal v) { this.hipoteca = v; }

        /** Tasa mensual asociada al tipo de cuenta ya normalizado. */
        public BigDecimal para(String tipoCuenta) {
            return switch (tipoCuenta) {
                case "ahorro" -> ahorro;
                case "prestamo" -> prestamo;
                case "hipoteca" -> hipoteca;
                default -> throw new IllegalArgumentException("Tipo de cuenta sin tasa configurada: " + tipoCuenta);
            };
        }

        /**
         * Signo del interes: en una cuenta de ahorro el interes abona
         * (+1); en prestamo e hipoteca el interes incrementa la deuda y se
         * registra como cargo sobre el saldo adeudado (+1 sobre el saldo
         * deudor, que es como viene expresado en el archivo legacy).
         */
        public int signo(String tipoCuenta) {
            return 1;
        }
    }

    public static class Benchmark {
        private int repeticiones = 5;
        private List<Integer> gridSizes = List.of(1, 2, 4, 8);
        private List<Integer> chunkSizes = List.of(50, 100, 250);
        private String archivoSalida = "evidencia/benchmark/resultados_benchmark.md";

        public int getRepeticiones() { return repeticiones; }
        public void setRepeticiones(int v) { this.repeticiones = v; }
        public List<Integer> getGridSizes() { return gridSizes; }
        public void setGridSizes(List<Integer> v) { this.gridSizes = v; }
        public List<Integer> getChunkSizes() { return chunkSizes; }
        public void setChunkSizes(List<Integer> v) { this.chunkSizes = v; }
        public String getArchivoSalida() { return archivoSalida; }
        public void setArchivoSalida(String v) { this.archivoSalida = v; }
    }
}
