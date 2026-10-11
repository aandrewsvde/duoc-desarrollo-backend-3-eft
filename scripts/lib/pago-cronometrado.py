#!/usr/bin/env python3
"""Resume en una linea el resultado y la duracion de crear un pago."""
import json, os, sys

ms = os.environ.get("MILISEGUNDOS", "?")
cuerpo = json.load(sys.stdin)

if "pago" not in cuerpo:
    print("   {:>6} ms | la peticion no devolvio un pago: {}".format(ms, cuerpo))
    sys.exit(0)

print("   {:>6} ms | estado={} | {}".format(
    ms, cuerpo["pago"]["estado"], cuerpo.get("observacion", "")[:118]))
