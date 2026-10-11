package com.duoc.bancoxyz.notificaciones.servicio;

import com.duoc.bancoxyz.notificaciones.dominio.Notificacion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bandeja en memoria con las ultimas notificaciones.
 *
 * <p>Una notificacion es un aviso efimero: una vez entregada deja de importar.
 * Guardarla en una base agregaria un contenedor, un esquema y una migracion
 * para conservar algo que nadie va a volver a leer.</p>
 *
 * <p>El tope de tamano no es un detalle: sin el, un proceso que lleva meses en
 * marcha acaba agotando la memoria. Al llegar al limite se descarta la mas
 * antigua, que es la que menos vale.</p>
 */
@Service
public class BandejaDeNotificaciones {

    private final Deque<Notificacion> recientes = new ArrayDeque<>();
    private final AtomicLong totalRecibidas = new AtomicLong();
    private final int maximo;

    public BandejaDeNotificaciones(@Value("${banco.notificaciones.maximo-en-memoria:200}") int maximo) {
        this.maximo = maximo;
    }

    /**
     * Agrega una notificacion descartando la mas antigua si se alcanzo el tope.
     *
     * <p>Sincronizado porque los consumidores de Kafka y las peticiones HTTP
     * corren en hilos distintos, y {@link ArrayDeque} no es segura entre hilos.
     * Con el volumen de este servicio, un bloqueo simple es mas claro y mas
     * barato que una estructura concurrente.</p>
     */
    public synchronized void agregar(Notificacion notificacion) {
        if (recientes.size() >= maximo) {
            recientes.removeLast();
        }
        recientes.addFirst(notificacion);
        totalRecibidas.incrementAndGet();
    }

    /** @return las mas recientes primero */
    public synchronized List<Notificacion> ultimas(int cuantas) {
        return recientes.stream().limit(Math.clamp(cuantas, 1, maximo)).toList();
    }

    public synchronized List<Notificacion> deCuenta(int cuentaId, int cuantas) {
        List<Notificacion> encontradas = new ArrayList<>();
        for (Notificacion notificacion : recientes) {
            if (notificacion.cuentaId() == cuentaId) {
                encontradas.add(notificacion);
                if (encontradas.size() == cuantas) {
                    break;
                }
            }
        }
        return encontradas;
    }

    public long totalRecibidas() {
        return totalRecibidas.get();
    }

    public synchronized int enMemoria() {
        return recientes.size();
    }
}
