#!/usr/bin/env python3
"""Resume el resultado de la migracion batch, paso a paso."""
import json, sys

d = json.load(sys.stdin)
print("   resultado: {}".format(d.get("resultado", "?")))
if d.get("detenida_en"):
    print("   detenida en: {}".format(d["detenida_en"]))

for ejecucion in d.get("ejecutados", []):
    if not ejecucion:
        continue
    print()
    print("   JOB {} -> {}".format(ejecucion.get("job"), ejecucion.get("estado")))
    for paso in ejecucion.get("pasos", []):
        # Las particiones se omiten: el paso padre ya agrega sus cifras, y
        # listarlas todas sepultaria el resumen bajo ocho lineas por job.
        if ":" in paso["nombre"]:
            continue
        print("      {:<34} leidos={:<6} escritos={:<6} omitidos={:<6} {}".format(
            paso["nombre"], paso["leidos"], paso["escritos"],
            paso["omitidos"], paso["estado"]))
