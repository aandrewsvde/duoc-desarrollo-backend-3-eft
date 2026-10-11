-- =====================================================================
-- Banco XYZ - Esquema de ms-pagos
--
-- Base propia: aqui no hay ninguna tabla de cuentas ni copia del saldo.
-- Cuando este servicio necesita saber si una cuenta existe, lo pregunta
-- por HTTP; no lo consulta en su propia base. Duplicar la tabla seria
-- comodo y seria exactamente el error que convierte un sistema de
-- microservicios en un monolito con varias bases.
-- =====================================================================

CREATE TABLE IF NOT EXISTS pago (
    id             VARCHAR(60)   PRIMARY KEY,
    cuenta_id      INTEGER       NOT NULL,
    tipo           VARCHAR(20)   NOT NULL,
    monto          NUMERIC(15,2) NOT NULL,
    moneda         VARCHAR(3)    NOT NULL DEFAULT 'CLP',
    -- Canal de origen. Permite responder por donde entro una operacion sin
    -- cruzar registros de varios servicios, que es la pregunta que mas repite
    -- operaciones cuando un titular reclama.
    canal          VARCHAR(10)   NOT NULL,
    estado         VARCHAR(20)   NOT NULL,
    motivo         VARCHAR(250),
    saldo_anterior NUMERIC(15,2),
    saldo_nuevo    NUMERIC(15,2),
    -- Referencia idempotente entregada por el cliente. La restriccion UNIQUE
    -- es lo que impide que un reintento del canal -un usuario que pulsa dos
    -- veces, una red que reenvia- genere dos pagos: la segunda choca
    -- contra el indice y se devuelve la primera.
    referencia     VARCHAR(80)   NOT NULL,
    creada_en      TIMESTAMP     NOT NULL DEFAULT now(),
    resuelta_en    TIMESTAMP,
    CONSTRAINT uq_pago_referencia UNIQUE (referencia),
    CONSTRAINT ck_pago_monto  CHECK (monto > 0),
    CONSTRAINT ck_pago_estado CHECK (estado IN ('PENDIENTE', 'CONFIRMADA', 'COMPENSADA')),
    CONSTRAINT ck_pago_tipo   CHECK (tipo IN ('DEPOSITO', 'RETIRO', 'TRANSFERENCIA', 'PAGO')),
    CONSTRAINT ck_pago_canal  CHECK (canal IN ('WEB', 'MOVIL', 'CAJERO'))
);
CREATE INDEX IF NOT EXISTS ix_pago_cuenta ON pago (cuenta_id, creada_en DESC);
CREATE INDEX IF NOT EXISTS ix_pago_estado ON pago (estado);
CREATE INDEX IF NOT EXISTS ix_pago_canal ON pago (canal, creada_en DESC);

-- Misma proteccion que en ms-cuentas, por el mismo motivo: los eventos de
-- resultado tambien pueden llegar repetidos.
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
