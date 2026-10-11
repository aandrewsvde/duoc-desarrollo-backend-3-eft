#!/usr/bin/env python3
"""
Cuenta cuantas veces respondio cada instancia.

Es la comprobacion del escalado horizontal: con varias replicas detras del
balanceador, el campo "instancia" de cada respuesta delata cual contesto, y
si el reparto es real aparecen varias.
"""
import collections, sys

conteo = collections.Counter(linea.strip() for linea in sys.stdin if linea.strip())
print("   {:<40}{}".format("INSTANCIA QUE RESPONDIO", "VECES"))
for instancia, veces in sorted(conteo.items()):
    print("   {:<40}{}".format(instancia, veces))
print("   instancias distintas: {}".format(len(conteo)))
