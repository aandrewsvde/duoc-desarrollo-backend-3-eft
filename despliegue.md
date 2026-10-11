# Despliegue en un entorno de nube (AWS)

Banco XYZ · Evaluación Final Transversal · Desarrollo Backend III (PBY2203)
Agustín Andrews V.

---

## Advertencia previa, que vale la pena leer

**Nada de lo que aquí se describe se ejecutó.** Este documento y los archivos
de `despliegue/aws/` son artefactos revisables: describen el despliegue y
contienen la plantilla de infraestructura, pero no se aplicó ninguna sobre una
cuenta real. Los recursos de AWS cuestan dinero desde el momento en que
existen, y un despliegue es una acción con consecuencias que debe decidir y
ejecutar una persona.

Lo que **sí** está probado de punta a punta es el sistema completo en
contenedores, con la evidencia en `evidencia/consola/`. El salto a AWS cambia
dónde corren los contenedores, no qué hacen.

---

## 1. Dos caminos, y por qué se documentan los dos

| | Camino A: EC2 con Compose | Camino B: ECS Fargate |
|---|---|---|
| Esfuerzo | Una tarde | Varios días |
| Coste aproximado | Una instancia | Una tarea por servicio, ALB, RDS, MSK |
| Escalado | Manual, dentro de la instancia | Automático por servicio |
| Alta disponibilidad | Ninguna: una instancia es un punto único de fallo | Por zona de disponibilidad |
| Viable en el laboratorio del curso | **Sí** | Parcialmente |

El **camino A** es el que corresponde a este entregable y al laboratorio de
AWS Academy: reproduce exactamente lo que ya funciona en local, en una máquina
de AWS, con los mismos archivos. El **camino B** es a dónde debería ir el
sistema en producción, y se documenta porque la decisión de arquitectura que
sostiene todo el proyecto —doce procesos independientes, sin estado, que se
descubren entre sí— es precisamente la que hace ese salto posible sin
reescribir código.

No se documenta el camino B como si fuera el entregable, porque no lo es.

---

## 2. Limitaciones reales del laboratorio de AWS Academy

Conviene decirlas antes, porque condicionan todo lo demás:

- **No se pueden crear roles de IAM.** Solo existe `LabRole`, ya creado. Toda
  plantilla que intente `AWS::IAM::Role` falla.
- **No está disponible Amazon MSK** (Kafka gestionado) en la mayoría de los
  laboratorios. Kafka tiene que correr como contenedor.
- **La sesión caduca** (habitualmente a las 4 horas) y las instancias se
  detienen. No es un entorno para dejar nada corriendo.
- **Región fija**, normalmente `us-east-1`.
- **Presupuesto acotado**, del orden de 50 USD para todo el curso.

Con esas restricciones, el camino A no es una simplificación: es la opción
correcta.

---

## 3. Camino A — EC2 con Docker Compose

### 3.1 Preparar la imagen de máquina

Desde la consola de EC2:

| Parámetro | Valor | Por qué |
|---|---|---|
| AMI | Amazon Linux 2023 | Trae `dnf` y Docker en los repositorios |
| Tipo | `t3.large` (2 vCPU, 8 GiB) | Doce JVM más PostgreSQL y Kafka no caben en 4 GiB; con `t3.medium` el arranque se vuelve errático por memoria |
| Almacenamiento | 30 GiB gp3 | Las imágenes ocupan unos 8 GiB con las capas compartidas |
| Par de claves | uno nuevo, descargarlo | Es la única forma de entrar |
| Rol de instancia | `LabRole` | El único disponible; permite leer de S3 si se quisiera |

### 3.2 Grupo de seguridad

Este es el punto donde más fácil se comete un error de seguridad, así que
conviene ser explícito:

| Puerto | Origen | Qué expone |
|---|---|---|
| 22 | **solo tu IP** | SSH |
| 8080 | tu IP, o `0.0.0.0/0` si hay que mostrarlo | Gateway: la única puerta de entrada |
| 9000 | tu IP | Servidor de autorización, para el flujo con navegador |

**Ningún otro puerto.** Los 8081–8085, 8090, 8091–8093, 8761, 8888, 5436 y
9092 **no se abren**: son de la red interna de Docker. Abrir el puerto de un
microservicio permitiría saltarse el gateway y, con él, la validación del
token en la frontera. El puerto 5436 expuesto sería la base de datos del banco
abierta a internet.

### 3.3 Instalar Docker y desplegar

```bash
ssh -i mi-clave.pem ec2-user@<IP-PUBLICA>

sudo dnf update -y
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user

# Compose v2 como plugin del usuario
mkdir -p ~/.docker/cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
     -o ~/.docker/cli-plugins/docker-compose
chmod +x ~/.docker/cli-plugins/docker-compose

exit   # volver a entrar para que el grupo docker surta efecto
```

Ya dentro otra vez:

```bash
git clone <URL-DEL-REPOSITORIO> banco-xyz
cd banco-xyz
./scripts/levantar.sh
./scripts/migrar.sh
```

### 3.4 El ajuste imprescindible: el emisor de los tokens

Esto es lo único que **no** funciona copiando los archivos tal cual, y conviene
entender por qué.

El servidor de autorización graba en cada token un campo `iss` con su propia
dirección, y los microservicios comprueban que coincida. En local esa dirección
es `http://localhost:9000`. Desde un navegador que apunta a una instancia EC2,
`localhost` es el propio portátil: el flujo de login redirige a un sitio que no
existe y los tokens emitidos no validan.

Hay que fijar la dirección pública en `docker-compose.yml`:

```yaml
  authorization-server:
    environment:
      OAUTH_ISSUER:       http://<IP-PUBLICA-O-DOMINIO>:9000
      OAUTH_REDIRECT_URI: http://<IP-PUBLICA-O-DOMINIO>:9000/autorizado
```

y en **todos** los servicios que validan tokens:

```yaml
x-comunes: &comunes
  ISSUER_URI:  http://<IP-PUBLICA-O-DOMINIO>:9000
  JWK_SET_URI: http://authorization-server:9000/oauth2/jwks
```

Nótese que son dos direcciones distintas y es deliberado: el **emisor** es la
dirección pública, porque es la que el navegador alcanza; las **claves** se
piden por el nombre del contenedor, porque ese tráfico nunca sale de la red de
Docker. Confundirlas es el error más común al llevar OAuth 2.0 a un servidor.

> Con una IP elástica esto se configura una vez. Sin ella, la IP cambia cada
> vez que la instancia se detiene, y hay que repetirlo.

### 3.5 Qué queda pendiente en este camino

Se dice aquí en lugar de dejarlo implícito:

- **HTTP sin cifrar.** Para TLS hace falta un certificado y un nombre de
  dominio; con una IP pública no hay forma de obtener uno de confianza. En
  producción esto lo resuelve un ALB con un certificado de ACM.
- **Una sola instancia.** Si se cae, se cae todo. No hay alta disponibilidad.
- **Los datos viven en un volumen de Docker.** Si se termina la instancia, se
  pierden. Deben estar en RDS.
- **Los secretos están en el `docker-compose.yml`.** Aceptable para una
  demostración; en producción van en Secrets Manager.

---

## 4. Camino B — ECS Fargate (hacia dónde debería ir)

### 4.1 Correspondencia entre lo local y lo gestionado

| Pieza local | En AWS | Por qué cambia |
|---|---|---|
| Contenedor de un servicio | Tarea de ECS Fargate | Sin servidores que administrar; escala por servicio |
| `docker-compose.yml` | Definiciones de tarea + servicios de ECS | El `depends_on` se traduce en orden de despliegue y sondas |
| Imágenes locales | Amazon ECR | Un repositorio por servicio |
| PostgreSQL en contenedor | Amazon RDS PostgreSQL | Copias de seguridad, failover, cifrado en reposo |
| Kafka en contenedor | Amazon MSK | Réplicas y retención gestionadas |
| **Eureka** | **AWS Cloud Map** | ECS registra y des-registra las tareas automáticamente |
| Gateway como puerta | ALB **delante** del gateway | El balanceo de entrada lo hace el ALB; el gateway sigue enrutando |
| Secretos en el compose | Secrets Manager | Rotación y auditoría |
| `logging` por consola | CloudWatch Logs | Los contenedores son efímeros; los registros no deben serlo |

**Sobre Eureka**: en ECS se puede conservar o sustituir. Conservarlo funciona y
no exige cambios de código; sustituirlo por Cloud Map elimina un servicio que
mantener y aprovecha que ECS ya sabe qué tareas están vivas. Para una migración
por fases, lo sensato es conservarlo al principio —el sistema se mueve sin
tocarse— y retirarlo después.

### 4.2 Lo que ya está resuelto, y por qué

El salto es viable sin reescribir porque el sistema ya cumple lo que Fargate
exige:

- **Sin estado en el proceso.** Ningún servicio guarda sesión; el token lleva
  la identidad. Una tarea puede morir y otra tomar su lugar.
- **Configuración por variables de entorno.** Todas las direcciones salen de
  variables, de modo que apuntar a RDS o a MSK es cambiar un valor, no código.
- **Sondas de salud ya definidas.** El `healthCheck` de la definición de tarea
  usa el mismo `/actuator/health` que ya usa Compose.
- **Una imagen por servicio.** Ya están separadas; solo hay que etiquetarlas
  para ECR.

### 4.3 Artefactos incluidos

| Archivo | Qué contiene |
|---|---|
| `despliegue/aws/subir-a-ecr.sh` | Etiqueta y sube las doce imágenes a ECR |
| `despliegue/aws/task-definition-ms-pagos.json` | Definición de tarea de ejemplo, comentada |
| `despliegue/aws/infraestructura.yaml` | Plantilla de CloudFormation: VPC, subredes, ALB, cluster, RDS |
| `despliegue/aws/docker-compose.produccion.yml` | Compose sin puertos de depuración ni panel de Kafka |

Ninguno se aplicó.

### 4.4 Secuencia

```bash
# 1. Subir imágenes (requiere credenciales de AWS configuradas)
./despliegue/aws/subir-a-ecr.sh us-east-1 <ID-DE-CUENTA>

# 2. Crear la infraestructura
aws cloudformation deploy \
    --template-file despliegue/aws/infraestructura.yaml \
    --stack-name banco-xyz \
    --parameter-overrides ClaveBaseDatos=<clave> \
    --capabilities CAPABILITY_IAM

# 3. Registrar las definiciones de tarea y crear los servicios
aws ecs register-task-definition \
    --cli-input-json file://despliegue/aws/task-definition-ms-pagos.json
```

El orden importa por la misma razón que en Compose: RDS y MSK deben existir y
aceptar conexiones antes de que arranque el primer servicio que los use.

### 4.5 Escalado en Fargate

Lo que en local se hace con `--scale` aquí se declara:

```bash
aws ecs update-service --cluster banco-xyz --service ms-pagos --desired-count 3

aws application-autoscaling put-scaling-policy \
    --policy-type TargetTrackingScaling \
    --resource-id service/banco-xyz/ms-pagos \
    --scalable-dimension ecs:service:DesiredCount \
    --target-tracking-scaling-policy-configuration \
      'TargetValue=70.0,PredefinedMetricSpecification={PredefinedMetricType=ECSServiceAverageCPUUtilization}'
```

**Qué se puede escalar y qué no**, que es la pregunta que importa:

| Servicio | ¿Escala? | Por qué |
|---|---|---|
| `ms-clientes`, `ms-cuentas`, `ms-pagos` | Sí | Sin estado; el estado está en RDS |
| `bff-web`, `bff-movil`, `bff-cajero` | Sí | Sin estado, solo transforman |
| `api-gateway` | Sí, con el ALB delante | El ALB reparte entre las tareas |
| `ms-notificaciones` | Sí, pero con matices | Guarda avisos en memoria: cada réplica tendría los suyos. Para escalar de verdad hay que sacarlos a una base |
| `config-server`, `authorization-server` | Sí | Sin estado; el servidor de autorización necesitaría claves compartidas en Secrets Manager en vez de generarlas al arrancar |
| `batch-financiero` | **No** | Es un proceso de un solo uso. En AWS corresponde a una tarea programada con EventBridge, no a un servicio permanente |

Sobre el último punto: hoy el batch arranca con el sistema y espera a que lo
disparen por REST. En AWS lo correcto es registrarlo como *task definition* y
lanzarlo con `aws ecs run-task` desde una regla de EventBridge, sin que haya
ninguna tarea corriendo entre ejecuciones.

---

## 5. Estimación de coste del camino B

Precios de referencia de `us-east-1`, orientativos y sin impuestos. Sirven para
ver el orden de magnitud, no para presupuestar.

| Recurso | Configuración | Aprox. mensual |
|---|---|---|
| ECS Fargate | 11 tareas × 0,5 vCPU / 1 GiB | 160 USD |
| RDS PostgreSQL | `db.t4g.micro`, 20 GiB | 15 USD |
| MSK | 2 brokers `kafka.t3.small` | 100 USD |
| ALB | 1, tráfico bajo | 20 USD |
| ECR | 12 repositorios, ~10 GiB | 1 USD |
| CloudWatch Logs | retención 7 días | 5 USD |
| **Total** | | **~300 USD** |

Excede con holgura el presupuesto del laboratorio del curso, que es la razón
práctica por la que el entregable va por el camino A.

---

## 6. Lo que habría que hacer antes de llamar a esto «producción»

Por orden de importancia:

1. **Secretos fuera del código.** Hoy los secretos de los clientes OAuth 2.0
   están escritos en `ConfiguracionAuthorizationServer`. Deben estar en Secrets
   Manager y leerse al arrancar.
2. **Claves de firma persistentes.** El servidor de autorización genera un par
   RSA nuevo en cada arranque; al reiniciarlo, los tokens vigentes dejan de
   validar. En producción la clave viene de un almacén y rota de forma
   controlada.
3. **TLS de extremo a extremo.** Certificado de ACM en el ALB y, dentro de la
   malla, al menos TLS entre servicios.
4. **Clientes OAuth 2.0 en base de datos.** Hoy están en memoria; darlos de
   alta exige reconstruir la imagen.
5. **Autenticar el endpoint del batch.** Hoy no lleva token: está protegido
   solo por no estar publicado. Debería tener su propio alcance.
6. **Trazabilidad distribuida.** El gateway ya marca cada petición con un
   identificador de correlación; falta propagarlo a los eventos de Kafka y
   enviarlo a X-Ray o a un recolector de OpenTelemetry.
7. **Más particiones por tópico.** Con una partición, solo una réplica de cada
   grupo consume. Para que el escalado sirva también del lado de los eventos
   hacen falta tantas particiones como consumidores.
