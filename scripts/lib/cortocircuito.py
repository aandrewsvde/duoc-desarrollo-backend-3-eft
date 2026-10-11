#!/usr/bin/env python3
"""
Resume el estado de un cortocircuito en una linea.

Vive en un archivo propio y no incrustado en el script de shell porque el
formateo necesita comillas dobles dentro de comillas dobles, y dentro de un
heredoc eso termina en un escapado fragil que falla en silencio.
"""
import json, sys

nombre = sys.argv[1] if len(sys.argv) > 1 else "cuentas"
datos = json.load(sys.stdin)["circuitBreakers"][nombre]

# -1 significa que la ventana todavia no reunio el minimo de llamadas que hace
# falta para evaluar la tasa; no es un valor negativo real.
tasa = datos["failureRate"]
tasa = "sin evaluar" if tasa.startswith("-") else tasa

# El actuator informa las llamadas EN LA VENTANA, no desde el arranque: las
# exitosas se deducen restando las fallidas a las almacenadas.
almacenadas = datos["bufferedCalls"]
fallidas = datos["failedCalls"]

print("   {:<10} estado={:<10} tasa={:<13} ventana={} fallidas={} exitosas={} rechazadas={}".format(
    nombre, datos["state"], tasa, almacenadas, fallidas,
    almacenadas - fallidas, datos["notPermittedCalls"]))
