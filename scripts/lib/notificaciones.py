#!/usr/bin/env python3
"""Muestra los avisos que genero ms-notificaciones."""
import json, sys
d = json.load(sys.stdin)
print("   recibidas desde el arranque: {}".format(d.get("recibidasDesdeElArranque")))
for n in d.get("notificaciones", []):
    print("   [{}/{}] {}".format(n["canal"], n["nivel"], n["mensaje"]))
