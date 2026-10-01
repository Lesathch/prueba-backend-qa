# Despliegue AWS opcional

La solución local no necesita AWS. Esta carpeta aprovisiona DynamoDB on-demand con cifrado, recuperación a un punto en el tiempo y protección de borrado, un registro ECR y roles IAM con acceso limitado a la tabla. Al definir una imagen, también crea App Runner con HTTPS y un máximo de dos instancias.

Requisitos: Terraform 1.6+, AWS CLI v2, Docker y una cuenta con permisos para DynamoDB, ECR, IAM y App Runner. Usa credenciales temporales (`aws sso login` o equivalente); no agregues claves a archivos versionados. El estado de Terraform es sensible y queda excluido de Git; para uso compartido configura un backend remoto antes de trabajar en equipo.

## 1. Persistencia y registro

Desde esta carpeta:

```bash
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform fmt -check
terraform validate
terraform plan -out=plan
terraform apply plan
terraform output -raw repository_url
```

`image_identifier` vacío crea persistencia, registro y configuración/roles; no crea la aplicación. Copia la URL del registro.

## 2. Construir y publicar imagen

Desde la raíz del proyecto, sustituye los valores de ejemplo con los de tu cuenta:

```bash
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com
docker build --platform linux/amd64 -t franchises-api:1.0.0 .
docker tag franchises-api:1.0.0 123456789012.dkr.ecr.us-east-1.amazonaws.com/franchises-api:1.0.0
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/franchises-api:1.0.0
```

ECR exige etiquetas inmutables: para una nueva versión usa otra etiqueta. También puedes fijar la imagen por digest para una entrega reproducible.

## 3. Aplicación

En `terraform.tfvars`, agrega `image_identifier` con la URI ECR completa. En esta carpeta ejecuta:

```bash
terraform plan -out=plan
terraform apply plan
terraform output -raw api_url
```

Comprueba `/actuator/health/readiness`, `/docs.html` y el flujo del API. En PowerShell puedes usar `../scripts/smoke.ps1 -BaseUrl https://TU-SERVICIO.awsapprunner.com`. App Runner utiliza un rol para leer ECR y otro para las operaciones sobre la tabla; la API no guarda claves AWS. La política DynamoDB contempla también `ConditionCheckItem`, necesario para las comprobaciones transaccionales de padres.

La URL de App Runner expone los endpoints sin autenticación. Para un entorno con datos reales hay que añadir autorización y limitar el tráfico; esta infraestructura está destinada a demostrar la prueba. La salud de App Runner usa liveness para evitar reinicios en cascada si la base de datos falla; readiness sigue disponible para comprobación operativa.

## Costes y eliminación

DynamoDB cobra por solicitudes y almacenamiento; PITR, ECR y App Runner también generan cargos. App Runner mantiene al menos una instancia provisionada, incluso sin tráfico. No se promete coste cero. Revisa las tarifas de tu región antes de aplicar.

Para eliminar la demostración, vacía ECR y cambia `deletion_protection_enabled` a `false` en la tabla, aplica ese cambio y revisa `terraform plan -destroy` antes de ejecutar `terraform destroy`. Esto elimina los datos. La protección habilitada por defecto evita un borrado accidental, por lo que un destroy directo puede fallar hasta deshabilitarla.

Referencia oficial: [App Runner con una imagen ECR](https://docs.aws.amazon.com/apprunner/latest/dg/service-source-image.html), [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.DownloadingAndRunning.html).
