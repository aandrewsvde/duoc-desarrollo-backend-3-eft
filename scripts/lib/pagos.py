#!/usr/bin/env python3
"""Lista pagos con su estado dentro de la saga."""
import json, sys
d = json.load(sys.stdin)
print("   {:<20}{:<13}{:<9}{:<14}{}".format(
    "PAGO", "ESTADO", "CANAL", "SALDO ANT.", "SALDO NUEVO"))
for p in d.get("pagos", []):
    print("   {:<20}{:<13}{:<9}{:<14}{}".format(
        p["id"], p["estado"], p["canal"],
        str(p.get("saldoAnterior")), str(p.get("saldoNuevo"))))
