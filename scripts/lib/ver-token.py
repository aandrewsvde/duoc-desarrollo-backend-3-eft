#!/usr/bin/env python3
"""
Muestra el contenido de un JWT sin verificar su firma.

Un JWT no esta cifrado: esta FIRMADO. Cualquiera puede leer lo que lleva
dentro, y lo que la firma garantiza es que nadie lo ha modificado. Este
ayudante existe para dejarlo en evidencia, y de paso para mostrar que el
emisor, el destinatario y los alcances son los que se esperan.
"""
import base64, json, sys

token = sys.argv[1]
partes = token.split(".")

def decodificar(parte):
    # base64 url-safe: hay que reponer el relleno que la especificacion omite.
    relleno = "=" * (-len(parte) % 4)
    return json.loads(base64.urlsafe_b64decode(parte + relleno))

print("   === Cabecera ===")
print("   " + json.dumps(decodificar(partes[0]), indent=3).replace("\n", "\n   "))
print("   === Cuerpo ===")
print("   " + json.dumps(decodificar(partes[1]), indent=3).replace("\n", "\n   "))
