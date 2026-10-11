package com.duoc.bancoxyz.pagos.cliente;

/**
 * Resultado de consultar a ms-cuentas antes de publicar el pago.
 *
 * <p>Modela explicitamente el caso de "no se pudo comprobar". Esa tercera
 * posibilidad es la que permite seguir aceptando pagos cuando
 * ms-cuentas no responde: la comprobacion previa es una cortesia para fallar
 * temprano, no la autoridad. La autoridad es ms-cuentas cuando aplica el
 * evento, y seguira rechazando lo que corresponda aunque aqui no se haya
 * podido anticipar.</p>
 *
 * @param verificada si se logro consultar a ms-cuentas
 * @param detalle    explicacion legible, util para la evidencia y los registros
 * @param cuenta     datos de la cuenta, o {@code null} si no se pudo consultar
 */
public record ValidacionPrevia(boolean verificada, String detalle, ResumenCuenta cuenta) {

    public static ValidacionPrevia confirmada(ResumenCuenta cuenta) {
        return new ValidacionPrevia(true,
                "Cuenta verificada en ms-cuentas: saldo " + cuenta.saldo() + " " + cuenta.moneda(),
                cuenta);
    }

    public static ValidacionPrevia noVerificada(String detalle) {
        return new ValidacionPrevia(false, detalle, null);
    }
}
