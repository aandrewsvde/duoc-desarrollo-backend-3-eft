-- Una base por microservicio, mas una para el batch, en una sola
-- instancia de PostgreSQL.
--
-- Son bases separadas y no esquemas dentro de una misma: asi ningun
-- servicio puede consultar las tablas de otro ni siquiera por error, que
-- es la frontera que sostiene su independencia. Compartir la instancia es
-- una concesion al tamano de este entregable; en produccion serian
-- instancias distintas y el codigo no cambiaria, porque cada servicio
-- solo conoce su propia URL.
CREATE DATABASE bancoxyz_clientes OWNER bancoxyz;
CREATE DATABASE bancoxyz_cuentas  OWNER bancoxyz;
CREATE DATABASE bancoxyz_pagos    OWNER bancoxyz;

-- El batch tiene la suya para su metadato de ejecucion y sus tablas de
-- trabajo. No comparte base con ningun microservicio: sus tablas son un
-- detalle del proceso de migracion, no datos operativos.
CREATE DATABASE bancoxyz_batch    OWNER bancoxyz;
