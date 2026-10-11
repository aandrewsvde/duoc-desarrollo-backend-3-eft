# Datos del sistema legado

## `legacy/`

Copia de <https://github.com/KariVillagran/fin_legacy_data>, el repositorio
que entrega el enunciado de la evaluacion. Trae tres conjuntos (`semana_1`,
`semana_2`, `semana_3`), cada uno con los mismos tres archivos y datos
distintos. La migracion usa `semana_3` por defecto; se cambia con la variable
`SEMANA_DATOS` del `docker-compose.yml`.

| Archivo | Proceso legado que alimenta | Columnas |
|---|---|---|
| `movimientos_financieros_diarios.csv` | Reporte de transacciones diarias | `id,fecha,monto,tipo` |
| `intereses_trimestrales.csv` | Calculo de intereses | `cuenta_id,nombre,saldo,edad,tipo` |
| `estados_financieros_anuales.csv` | Estados de cuenta anuales | `cuenta_id,fecha,transaccion,monto,descripcion` |

Los archivos traen defectos **intencionales**, que es justamente lo que el
proceso batch debe saber manejar. Medidos sobre `semana_3`:

| Defecto | Donde | Cuantos |
|---|---|---|
| Cuatro formatos de fecha conviviendo (`yyyy/MM/dd`, `yyyy-MM-dd`, `dd-MM-yyyy`, `dd/MM/yyyy`) | los tres archivos | 1000 de 1000 |
| Montos vacios | movimientos diarios | 168 |
| Montos negativos | movimientos diarios | 125 |
| Montos en cero | movimientos diarios | 20 |
| Tipo de transaccion invalido (`invalido`, `desconocido`) | movimientos diarios | 383 |
| Saldos vacios | intereses | 204 |
| Edades vacias | intereses | 171 |
| Edades fuera de rango | intereses | 46 |
| Tipo de cuenta como marcador de relleno (`-1`, `unknown`) | intereses | 325 |
| Titular `Unknown` | intereses | 48 |
| Descripciones vacias | estados anuales | 184 |
| Tipos con y sin tilde conviviendo (`deposito` / `deposito`) | estados anuales | 51 |

El archivo de intereses trae 1000 filas para solo **50 cuentas** (101 a 150):
son unas 20 filas contradictorias por cuenta, que es la forma que toma aqui el
problema de "registros duplicados" que menciona el repositorio de origen.

## `salida/`

Lo que produce la migracion: informes CSV y de texto, y el volcado de los
registros rechazados. Se monta como volumen en el contenedor del batch, de modo
que los archivos quedan en el anfitrion y se pueden revisar sin entrar al
contenedor.
