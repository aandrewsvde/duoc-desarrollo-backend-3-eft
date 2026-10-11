-- =====================================================================
-- Banco XYZ - Esquema de ms-clientes
--
-- Es la base con los datos mas sensibles del sistema. Por eso guarda lo
-- minimo: nombre, edad y segmento comercial. No hay RUT, ni direccion,
-- ni telefono, ni correo. Un dato que no se almacena no se puede filtrar,
-- y el caso no necesita ninguno de ellos para funcionar.
-- =====================================================================

CREATE SEQUENCE IF NOT EXISTS seq_cliente START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS cliente (
    id             INTEGER       PRIMARY KEY,
    nombre         VARCHAR(120)  NOT NULL,
    edad           INTEGER,
    -- Segmento comercial. NO se declara por fuera: lo recalcula el propio
    -- servicio al enterarse de que un saldo cambio. Dejar que un canal lo
    -- escribiera permitiria que un cliente se auto-asignara beneficios.
    segmento       VARCHAR(20)   NOT NULL DEFAULT 'ESTANDAR',
    saldo_total    NUMERIC(15,2) NOT NULL DEFAULT 0,
    estado         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVO',
    creado_en      TIMESTAMP     NOT NULL DEFAULT now(),
    actualizado_en TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT ck_cliente_estado   CHECK (estado   IN ('ACTIVO', 'SUSPENDIDO', 'BAJA')),
    CONSTRAINT ck_cliente_segmento CHECK (segmento IN ('ESTANDAR', 'PREFERENTE')),
    -- La edad es opcional porque el legado la trae vacia en muchas filas, pero
    -- si viene debe ser plausible: el batch ya descarta las imposibles y esta
    -- restriccion impide que entren por otra via.
    CONSTRAINT ck_cliente_edad     CHECK (edad IS NULL OR (edad BETWEEN 18 AND 100))
);
CREATE INDEX IF NOT EXISTS ix_cliente_nombre ON cliente (nombre);

-- ---------------------------------------------------------------------
-- Saldo por cuenta, visto desde el cliente.
--
-- Es una copia derivada de lo que vive en ms-cuentas, construida a partir
-- de los eventos. Existe por una razon concreta: para saber si un cliente
-- es preferente hace falta sumar TODAS sus cuentas, y preguntarselo a
-- ms-cuentas en cada consulta acoplaria los dos servicios justo en el
-- camino mas transitado.
--
-- La fuente de verdad sigue siendo ms-cuentas. Esta tabla puede ir un
-- instante por detras, y eso es aceptable porque el segmento comercial no
-- necesita ser exacto al segundo.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS saldo_por_cuenta (
    cuenta_id      INTEGER       PRIMARY KEY,
    cliente_id     INTEGER       NOT NULL REFERENCES cliente (id),
    saldo          NUMERIC(15,2) NOT NULL,
    actualizado_en TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_saldo_cliente ON saldo_por_cuenta (cliente_id);

CREATE TABLE IF NOT EXISTS evento_procesado (
    evento_id    VARCHAR(60) PRIMARY KEY,
    topico       VARCHAR(80) NOT NULL,
    procesado_en TIMESTAMP   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Este archivo NO borra datos.
--
-- Se ejecuta en CADA arranque del servicio, de modo que un DELETE aqui
-- convertiria cualquier reinicio -un despliegue, una caida, escalar a
-- mas replicas- en una perdida de datos silenciosa. Eso fue exactamente
-- lo que ocurrio al probar la recuperacion de un servicio caido: volvio
-- sano y sin nada dentro.
--
-- Los datos los pone la migracion batch y se reinician donde corresponde:
-- borrando el volumen de PostgreSQL (scripts/detener.sh --limpiar) y
-- volviendo a migrar. Las sentencias CREATE llevan IF NOT EXISTS, asi que
-- reejecutar este archivo es inocuo.
-- ---------------------------------------------------------------------
