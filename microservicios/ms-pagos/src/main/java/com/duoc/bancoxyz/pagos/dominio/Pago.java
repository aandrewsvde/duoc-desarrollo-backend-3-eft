package com.duoc.bancoxyz.pagos.dominio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pago tal como lo ve el exterior, con su estado dentro de la saga.
 *
 * @param tipo   DEPOSITO, RETIRO, TRANSFERENCIA o PAGO
 * @param canal  por donde entro la operacion: WEB, MOVIL o CAJERO. Se guarda
 *               porque la misma operacion tiene distinto riesgo segun su
 *               origen, y porque sin el no se puede responder a la pregunta
 *               mas frecuente de operaciones, que es por que canal entro algo
 * @param motivo razon del rechazo; {@code null} mientras no haya rechazo
 */
public record Pago(
        String id,
        int cuentaId,
        String tipo,
        BigDecimal monto,
        String moneda,
        String canal,
        EstadoPago estado,
        String motivo,
        BigDecimal saldoAnterior,
        BigDecimal saldoNuevo,
        String referencia,
        Instant creadoEn,
        Instant resueltoEn) {
}
