#!/usr/bin/env python3
"""Extrae el access_token de una respuesta del servidor de autorizacion."""
import json, sys

respuesta = json.load(sys.stdin)
if "access_token" not in respuesta:
    print("ERROR: no se obtuvo token -> {}".format(respuesta), file=sys.stderr)
    sys.exit(1)
print(respuesta["access_token"])
