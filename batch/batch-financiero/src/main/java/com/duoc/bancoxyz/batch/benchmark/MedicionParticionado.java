package com.duoc.bancoxyz.batch.benchmark;

/** Una corrida medida del benchmark. */
public record MedicionParticionado(
        String jobName,
        int gridSize,
        int chunkSize,
        int repeticion,
        long duracionJobMs,
        long duracionCargaMs,
        long leidos,
        long escritos,
        long omitidosEnLectura,
        long omitidosEnProceso,
        long commits,
        String estado) {

    /**
     * Registros del archivo que entraron a la corrida.
     *
     * <p>Los descartados al leer nunca llegan a {@code readCount}, asi que
     * hay que sumarlos aparte; los descartados al procesar, en cambio, ya
     * estan contados como leidos y sumarlos otra vez inflaria el total.</p>
     */
    public long registrosDeEntrada() {
        return leidos + omitidosEnLectura;
    }

    public long omitidos() {
        return omitidosEnLectura + omitidosEnProceso;
    }
}
