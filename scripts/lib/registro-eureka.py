#!/usr/bin/env python3
"""Lista las instancias registradas en Eureka."""
import json, sys

aplicaciones = json.load(sys.stdin)["applications"].get("application", [])
if isinstance(aplicaciones, dict):
    aplicaciones = [aplicaciones]

print("   {:<26}{:<10}{}".format("SERVICIO", "ESTADO", "INSTANCIA"))
for aplicacion in sorted(aplicaciones, key=lambda a: a["name"]):
    instancias = aplicacion["instance"]
    if isinstance(instancias, dict):
        instancias = [instancias]
    for instancia in instancias:
        print("   {:<26}{:<10}{}".format(
            aplicacion["name"], instancia["status"], instancia["instanceId"]))
