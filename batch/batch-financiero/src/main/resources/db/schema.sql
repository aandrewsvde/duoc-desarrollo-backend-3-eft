-- =====================================================================
-- Banco XYZ - Esquema de negocio para la migracion batch (Spring Batch)
-- Las tablas de metadata de Spring Batch (BATCH_JOB_*) las crea el
-- propio framework mediante spring.batch.jdbc.initialize-schema=always.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Cuarentena comun a los tres jobs: TODO registro de entrada que no
-- supera las validaciones queda aqui con su motivo. De este modo se
-- cumple la trazabilidad "registros validos + rechazados = registros de
-- entrada" para cada ejecucion.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registro_rechazado (
    id               BIGSERIAL PRIMARY KEY,
    job_execution_id BIGINT,
    job_name         VARCHAR(80)  NOT NULL,
    step_name        VARCHAR(120) NOT NULL,
    fase             VARCHAR(20)  NOT NULL,   -- LECTURA | PROCESO | ESCRITURA
    linea_origen     TEXT,
    motivo           VARCHAR(300) NOT NULL,
    excepcion        VARCHAR(200) NOT NULL,
    hilo             VARCHAR(60),
    detectado_en     TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_registro_rechazado_job ON registro_rechazado (job_name, job_execution_id);

-- ---------------------------------------------------------------------
-- JOB 1 - Reporte de transacciones diarias
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaccion (
    id               BIGINT PRIMARY KEY,
    fecha            DATE           NOT NULL,
    monto            NUMERIC(15,2)  NOT NULL,
    tipo             VARCHAR(20)    NOT NULL,
    fecha_origen     VARCHAR(20)    NOT NULL,
    formato_detectado VARCHAR(20)   NOT NULL,
    job_execution_id BIGINT,
    procesado_en     TIMESTAMP      NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_transaccion_fecha ON transaccion (fecha);

CREATE TABLE IF NOT EXISTS anomalia_transaccion (
    id               BIGSERIAL PRIMARY KEY,
    transaccion_id   BIGINT,
    fecha            DATE,
    tipo_anomalia    VARCHAR(60)  NOT NULL,
    detalle          VARCHAR(300) NOT NULL,
    job_execution_id BIGINT,
    detectado_en     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS resumen_transaccion_diaria (
    fecha                  DATE PRIMARY KEY,
    total_transacciones    INTEGER       NOT NULL,
    total_creditos         INTEGER       NOT NULL,
    total_debitos          INTEGER       NOT NULL,
    monto_total            NUMERIC(18,2) NOT NULL,
    monto_creditos         NUMERIC(18,2) NOT NULL,
    monto_debitos          NUMERIC(18,2) NOT NULL,
    monto_neto             NUMERIC(18,2) NOT NULL,
    monto_promedio         NUMERIC(18,2) NOT NULL,
    monto_maximo           NUMERIC(15,2) NOT NULL,
    monto_minimo           NUMERIC(15,2) NOT NULL,
    transacciones_anomalas INTEGER       NOT NULL,
    job_execution_id       BIGINT,
    generado_en            TIMESTAMP     NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- JOB 2 - Calculo de intereses mensuales
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cuenta_interes (
    id               BIGSERIAL PRIMARY KEY,
    cuenta_id        INTEGER        NOT NULL,
    nombre           VARCHAR(120)   NOT NULL,
    tipo_cuenta      VARCHAR(20)    NOT NULL,
    edad             INTEGER        NOT NULL,
    saldo_inicial    NUMERIC(15,2)  NOT NULL,
    tasa_aplicada    NUMERIC(8,5)   NOT NULL,
    interes_calculado NUMERIC(15,2) NOT NULL,
    saldo_final      NUMERIC(15,2)  NOT NULL,
    periodo          VARCHAR(7)     NOT NULL,
    job_execution_id BIGINT,
    procesado_en     TIMESTAMP      NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_cuenta_interes_cuenta ON cuenta_interes (cuenta_id);

CREATE TABLE IF NOT EXISTS resumen_interes_mensual (
    id                 BIGSERIAL PRIMARY KEY,
    periodo            VARCHAR(7)    NOT NULL,
    tipo_cuenta        VARCHAR(20)   NOT NULL,
    total_cuentas      INTEGER       NOT NULL,
    tasa_aplicada      NUMERIC(8,5)  NOT NULL,
    saldo_inicial_total NUMERIC(18,2) NOT NULL,
    interes_total      NUMERIC(18,2) NOT NULL,
    saldo_final_total  NUMERIC(18,2) NOT NULL,
    job_execution_id   BIGINT,
    generado_en        TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_resumen_interes UNIQUE (periodo, tipo_cuenta)
);

-- ---------------------------------------------------------------------
-- JOB 3 - Generacion de estados de cuenta anuales
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS movimiento_anual (
    id               BIGSERIAL PRIMARY KEY,
    cuenta_id        INTEGER       NOT NULL,
    fecha            DATE          NOT NULL,
    anio             INTEGER       NOT NULL,
    tipo_movimiento  VARCHAR(20)   NOT NULL,
    signo            SMALLINT      NOT NULL,   -- +1 abona, -1 carga
    monto            NUMERIC(15,2) NOT NULL,
    monto_con_signo  NUMERIC(15,2) NOT NULL,
    descripcion      VARCHAR(200)  NOT NULL,
    fecha_origen     VARCHAR(20)   NOT NULL,
    formato_detectado VARCHAR(20)  NOT NULL,
    job_execution_id BIGINT,
    procesado_en     TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_movimiento_anual_cuenta ON movimiento_anual (cuenta_id, anio);

CREATE TABLE IF NOT EXISTS estado_cuenta_anual (
    id                 BIGSERIAL PRIMARY KEY,
    cuenta_id          INTEGER       NOT NULL,
    anio               INTEGER       NOT NULL,
    total_movimientos  INTEGER       NOT NULL,
    total_depositos    INTEGER       NOT NULL,
    total_retiros      INTEGER       NOT NULL,
    total_compras      INTEGER       NOT NULL,
    total_pagos        INTEGER       NOT NULL,
    monto_abonos       NUMERIC(18,2) NOT NULL,
    monto_cargos       NUMERIC(18,2) NOT NULL,
    saldo_neto         NUMERIC(18,2) NOT NULL,
    primer_movimiento  DATE          NOT NULL,
    ultimo_movimiento  DATE          NOT NULL,
    job_execution_id   BIGINT,
    generado_en        TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_estado_cuenta_anual UNIQUE (cuenta_id, anio)
);

-- ---------------------------------------------------------------------
-- Bitacora de benchmark: una fila por ejecucion medida (criterio 4 de la
-- pauta: comparar parametros para hallar la configuracion optima).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS benchmark_ejecucion (
    id                BIGSERIAL PRIMARY KEY,
    job_name          VARCHAR(80) NOT NULL,
    grid_size         INTEGER     NOT NULL,
    chunk_size        INTEGER     NOT NULL,
    repeticion        INTEGER     NOT NULL,
    duracion_ms       BIGINT      NOT NULL,
    leidos            INTEGER     NOT NULL,
    escritos          INTEGER     NOT NULL,
    omitidos          INTEGER     NOT NULL,
    commits           INTEGER     NOT NULL,
    estado            VARCHAR(20) NOT NULL,
    ejecutado_en      TIMESTAMP   NOT NULL DEFAULT now()
);
