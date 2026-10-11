# Proyecto de Datos Legacy de Finanzas ABC

Este proyecto proporciona los datos de entrada necesarios para un desafío de migración y modernización de procesos batch en Finanzas ABC. Los datos están organizados en archivos CSV que simulan el sistema legacy actual, utilizado para generar informes financieros y cálculos operativos. El objetivo es utilizar estos datos para migrar los procesos batch a una arquitectura moderna utilizando Spring Batch.

## Estructura del Proyecto

El proyecto tiene la siguiente estructura de directorios y archivos.

### Archivos CSV

Los archivos en el directorio `data/` contienen los datos de entrada que deben ser procesados en los procesos batch:

1. **`movimientos_financieros_diarios.csv`**: 
   - Contiene registros de transacciones financieras diarias, incluyendo datos como fecha, monto y tipo de transacción. 
   - Errores intencionales: montos negativos, fechas en formato incorrecto y tipos de transacción no válidos.

2. **`intereses_trimestrales.csv`**: 
   - Proporciona información sobre las cuentas y el cálculo de intereses trimestrales. Incluye datos como el saldo de la cuenta y la edad del titular.
   - Errores intencionales: registros duplicados, saldos faltantes y edades fuera del rango razonable.

3. **`estados_financieros_anuales.csv`**: 
   - Incluye el historial anual de operaciones de las cuentas, con detalles sobre depósitos, retiros y otros movimientos.
   - Errores intencionales: montos negativos, fechas mal formateadas y descripciones vacías.

## Problemas Simulados en los Datos

Los archivos CSV contienen intencionalmente problemas típicos de datos para simular casos reales que deben gestionarse en el proceso batch:
- **Montos negativos o valores cero**: Para detectar posibles errores o situaciones inusuales en las transacciones.
- **Campos vacíos o nulos**: Como descripciones de transacción o saldos de cuentas.
- **Fechas en formato incorrecto**: Varias fechas utilizan formatos no estándar (`yyyy/MM/dd`).
- **Registros duplicados**: Para simular inconsistencias en los datos.
- **Valores fuera de rango**: Por ejemplo, edades no válidas o tipos de transacción desconocidos.

Estos problemas deberán ser gestionados mediante políticas de reintento y omisión en Spring Batch para garantizar la integridad y calidad de los datos procesados.


**¡Buena suerte con el desafío!**