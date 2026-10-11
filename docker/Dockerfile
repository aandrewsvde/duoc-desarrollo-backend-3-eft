# =====================================================================
# Imagen multietapa para los doce servicios Spring del Banco XYZ.
#
# Un solo archivo con una etapa final por servicio, en lugar de doce
# Dockerfile. La ventaja no es estetica: la etapa de compilacion se
# construye UNA vez y las doce etapas finales la reutilizan, de modo que
# el reactor Maven se ejecuta una sola vez por build completo. Con doce
# archivos separados se compilaria doce veces lo mismo.
#
# Uso:  docker build -f docker/Dockerfile --target ms-cuentas .
#       (docker-compose.yml lo hace por cada servicio)
# =====================================================================

# ---------------------------------------------------------------------
# Etapa 1: compilacion
# ---------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS compilacion
WORKDIR /proyecto

# Primero solo los pom.xml y luego las dependencias. Docker cachea cada
# instruccion por separado, de modo que mientras no cambie un pom esta
# capa se reutiliza y la descarga de dependencias -lo que mas tarda- no
# se repite aunque se haya modificado el codigo fuente.
COPY pom.xml .
COPY shared/eventos-contrato/pom.xml            shared/eventos-contrato/
COPY shared/canal-commons/pom.xml               shared/canal-commons/
COPY plataforma/config-server/pom.xml           plataforma/config-server/
COPY plataforma/eureka-server/pom.xml           plataforma/eureka-server/
COPY plataforma/authorization-server/pom.xml    plataforma/authorization-server/
COPY plataforma/api-gateway/pom.xml             plataforma/api-gateway/
COPY batch/batch-financiero/pom.xml             batch/batch-financiero/
COPY microservicios/ms-clientes/pom.xml         microservicios/ms-clientes/
COPY microservicios/ms-cuentas/pom.xml          microservicios/ms-cuentas/
COPY microservicios/ms-pagos/pom.xml            microservicios/ms-pagos/
COPY microservicios/ms-notificaciones/pom.xml   microservicios/ms-notificaciones/
COPY bff/bff-web/pom.xml                        bff/bff-web/
COPY bff/bff-movil/pom.xml                      bff/bff-movil/
COPY bff/bff-cajero/pom.xml                     bff/bff-cajero/
RUN mvn -B -ntp dependency:go-offline -DskipTests

COPY shared         shared
COPY plataforma     plataforma
COPY batch          batch
COPY microservicios microservicios
COPY bff            bff
RUN mvn -B -ntp -DskipTests package

# ---------------------------------------------------------------------
# Etapa 2: base de ejecucion
#
# Imagen JRE y no JDK: la de ejecucion no necesita compilador, y
# prescindir de el reduce el tamano y quita herramientas que no deberian
# estar disponibles dentro de un contenedor en produccion.
# ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS base
# curl es la unica herramienta extra, y esta porque los healthcheck de
# Compose se ejecutan DENTRO del contenedor: sin ella no hay forma de
# consultar /actuator/health y "depends_on: service_healthy" no sirve.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Usuario sin privilegios: si alguien lograra ejecutar codigo dentro del
# contenedor, no lo haria como root.
#
# Se REUTILIZA el usuario 1000 de la imagen base en vez de crear otro, y
# el motivo es concreto: el batch escribe sus informes en un volumen
# montado desde el anfitrion, cuyos archivos pertenecen al usuario que
# los creo alli -1000 en la practica totalidad de las instalaciones
# Linux-. Un usuario con otro identificador no podria escribir en su
# propia carpeta de salida, y el fallo aparece a mitad del job, no al
# arrancar, que es mucho mas costoso de diagnosticar.
#
# Crear un usuario nuevo con --uid 1000 no sirve: ese identificador ya
# esta tomado en la imagen y useradd falla. Por eso se renombra el
# existente en lugar de anadir uno.
#
# En un despliegue real este acoplamiento no existe: la salida va a S3 o
# a un volumen gestionado, no a una carpeta del anfitrion.
RUN set -eux; \
    usuario="$(getent passwd 1000 | cut -d: -f1)"; \
    grupo="$(getent group 1000 | cut -d: -f1)"; \
    if [ -n "$grupo" ]; then groupmod -n banco "$grupo"; else groupadd --gid 1000 banco; fi; \
    if [ -n "$usuario" ]; then usermod -l banco -d /home/banco -m "$usuario"; \
    else useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash banco; fi
USER banco
WORKDIR /app

# La zona horaria importa en un sistema bancario: sin esto el contenedor
# usa UTC y las marcas de tiempo no coinciden con las que ve el usuario.
ENV TZ=America/Santiago
# El contenedor ya impone un limite de memoria; se le dice a la JVM que lo
# respete en lugar de calcular el monton sobre la memoria del anfitrion.
#
# ActiveProcessorCount=2 merece explicacion, porque es lo que evita que este
# entregable caliente la maquina hasta el limite termico.
#
# Sin el, CADA una de las doce JVM mira /proc/cpuinfo, ve 16 hilos y
# dimensiona en consecuencia sus hilos de recoleccion de basura, de
# compilacion JIT, el ForkJoinPool comun y los pools de Tomcat. Doce
# procesos convencidos cada uno de tener la maquina entera para si suman
# del orden de doscientos hilos listos para ejecutarse, y el arranque
# simultaneo clava los dieciseis hilos del procesador al 100% durante
# minutos. En un portatil de 15 W eso significa 95 grados sostenidos y
# limitacion termica.
#
# Con 2, cada servicio sigue arrancando igual de bien -ninguno necesita
# mas para atender la carga de una demostracion- y el pico desaparece.
# Es ademas lo correcto en cualquier despliegue denso: en ECS o Kubernetes
# la JVM debe dimensionarse por la cuota de la tarea, no por el tamano de
# la maquina anfitriona.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:ActiveProcessorCount=2"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# ---------------------------------------------------------------------
# Etapa 3: una imagen final por servicio
#
# Cada una copia unicamente su propio jar. Asi la imagen de bff-movil no
# contiene el codigo de ms-cuentas, que es la independencia de despliegue
# que se busca con microservicios.
# ---------------------------------------------------------------------
FROM base AS config-server
COPY --from=compilacion /proyecto/plataforma/config-server/target/*.jar /app/app.jar
EXPOSE 8888

FROM base AS eureka-server
COPY --from=compilacion /proyecto/plataforma/eureka-server/target/*.jar /app/app.jar
EXPOSE 8761

FROM base AS authorization-server
COPY --from=compilacion /proyecto/plataforma/authorization-server/target/*.jar /app/app.jar
EXPOSE 9000

FROM base AS api-gateway
COPY --from=compilacion /proyecto/plataforma/api-gateway/target/*.jar /app/app.jar
EXPOSE 8080

FROM base AS batch-financiero
COPY --from=compilacion /proyecto/batch/batch-financiero/target/*.jar /app/app.jar
EXPOSE 8085

FROM base AS ms-clientes
COPY --from=compilacion /proyecto/microservicios/ms-clientes/target/*.jar /app/app.jar
EXPOSE 8084

FROM base AS ms-cuentas
COPY --from=compilacion /proyecto/microservicios/ms-cuentas/target/*.jar /app/app.jar
EXPOSE 8081

FROM base AS ms-pagos
COPY --from=compilacion /proyecto/microservicios/ms-pagos/target/*.jar /app/app.jar
EXPOSE 8082

FROM base AS ms-notificaciones
COPY --from=compilacion /proyecto/microservicios/ms-notificaciones/target/*.jar /app/app.jar
EXPOSE 8083

FROM base AS bff-web
COPY --from=compilacion /proyecto/bff/bff-web/target/*.jar /app/app.jar
EXPOSE 8091

FROM base AS bff-movil
COPY --from=compilacion /proyecto/bff/bff-movil/target/*.jar /app/app.jar
EXPOSE 8092

FROM base AS bff-cajero
COPY --from=compilacion /proyecto/bff/bff-cajero/target/*.jar /app/app.jar
EXPOSE 8093
