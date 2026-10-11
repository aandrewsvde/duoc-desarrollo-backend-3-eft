package com.duoc.bancoxyz.cuentas.servicio;

import com.duoc.bancoxyz.cuentas.dominio.Cuenta;
import com.duoc.bancoxyz.cuentas.dominio.Movimiento;
import com.duoc.bancoxyz.cuentas.repositorio.CuentaDao;
import java.util.List;
import org.springframework.stereotype.Service;

/** Consultas de solo lectura sobre cuentas y movimientos. */
@Service
public class CuentaServicio {

    private final CuentaDao cuentas;

    public CuentaServicio(CuentaDao cuentas) {
        this.cuentas = cuentas;
    }

    public List<Cuenta> listar(int limite) {
        return cuentas.listar(Math.clamp(limite, 1, 200));
    }

    public Cuenta obtener(int numeroCuenta) {
        return cuentas.buscar(numeroCuenta)
                .orElseThrow(() -> new CuentaNoEncontradaException(numeroCuenta));
    }

    public List<Movimiento> movimientos(int numeroCuenta, int limite) {
        if (!cuentas.existe(numeroCuenta)) {
            throw new CuentaNoEncontradaException(numeroCuenta);
        }
        return cuentas.movimientosDe(numeroCuenta, Math.clamp(limite, 1, 200));
    }
}
