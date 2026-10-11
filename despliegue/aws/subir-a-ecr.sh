#!/usr/bin/env bash
#
# Sube las doce imagenes a Amazon ECR, una por servicio.
#
# NO lo ejecuta nadie automaticamente: crea repositorios y sube capas, que son
# recursos que cuestan dinero. Es un artefacto para revisar y, si se decide,
# ejecutar a mano.
#
# Uso:  ./subir-a-ecr.sh <region> <id-de-cuenta>
set -euo pipefail

REGION="${1:?Falta la region, por ejemplo us-east-1}"
CUENTA="${2:?Falta el id de cuenta de AWS}"
REGISTRO="${CUENTA}.dkr.ecr.${REGION}.amazonaws.com"
VERSION="${VERSION:-1.0.0}"

SERVICIOS=(
    config-server eureka-server authorization-server api-gateway
    batch-financiero
    ms-clientes ms-cuentas ms-pagos ms-notificaciones
    bff-web bff-movil bff-cajero
)

echo "==> Autenticando contra ${REGISTRO}"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$REGISTRO"

for servicio in "${SERVICIOS[@]}"; do
    echo "==> ${servicio}"

    # Un repositorio por servicio. Uno solo con etiquetas distintas seria mas
    # corto de escribir y mucho peor de operar: los permisos, las politicas de
    # retencion y el escaneo de vulnerabilidades se aplican por repositorio, y
    # no todos los servicios tienen el mismo riesgo.
    aws ecr describe-repositories --region "$REGION" --repository-names "banco-xyz/${servicio}" \
        >/dev/null 2>&1 \
      || aws ecr create-repository --region "$REGION" \
             --repository-name "banco-xyz/${servicio}" \
             --image-scanning-configuration scanOnPush=true \
             --image-tag-mutability IMMUTABLE >/dev/null

    docker tag "bancoxyz/${servicio}:${VERSION}" "${REGISTRO}/banco-xyz/${servicio}:${VERSION}"
    docker push "${REGISTRO}/banco-xyz/${servicio}:${VERSION}"
done

echo
echo "Listo. Las imagenes quedaron en ${REGISTRO}/banco-xyz/<servicio>:${VERSION}"
echo
echo "Nota: las etiquetas son INMUTABLES a proposito. Sobrescribir una etiqueta"
echo "existente hace que 'la version 1.0.0' signifique cosas distintas segun"
echo "cuando se descargue, y entonces un despliegue deja de ser reproducible."
