# Verificación de la entrega

Verificado localmente el **1 de octubre de 2026**, zona America/Bogota. Este documento registra resultados observados; no acredita ejecución de CI ni despliegue en AWS.

| Comprobación | Resultado |
| --- | --- |
| Compilación y empaquetado del JAR | `BUILD SUCCESS` con Java 21 |
| Pruebas unitarias | 6 exitosas, 0 fallos, 0 omitidas |
| Pruebas de integración HTTP y DynamoDB | 13 exitosas, 0 fallos, 0 omitidas |
| Persistencia real para la última suite | DynamoDB Local **3.3.1**, distribución oficial verificada con SHA-256 |
| Cobertura JaCoCo | **150/163 líneas (92.0%)**, **38/50 ramas (76.0%)** |
| Maven Wrapper | Maven **3.9.11**, Java **21.0.12.1** |
| Contrato OpenAPI | 14 operaciones; referencias de esquemas resueltas; servido por la API |
| Swagger UI | HTML, JavaScript y CSS locales responden `200` |
| Demostración PowerShell | Creación, tres renombramientos, stock, máximo y eliminación exitosos |
| Persistencia tras reinicio | Franquicia creada, ambos procesos detenidos y reiniciados; el GET conserva nombre e ID |
| Salud de aplicación empaquetada | Readiness `200`, `{"status":"UP"}` |
| Terraform | `fmt -check` y `validate` exitosos; proveedor AWS fijado en `.terraform.lock.hcl` |
| Git | Historia local con commits por implementación, operación y documentación |

La suite de integración verifica:

- Ciclo completo y tres cambios de nombre.
- UUIDs, JSON malformado, nombres vacíos/excesivos y campos desconocidos.
- Stock ausente, negativo, decimal y fuera de rango; stock cero permitido.
- Padres ausentes y separación entre sucursales/franquicias.
- Versiones obsoletas y dos escritores concurrentes: uno recibe `200` y el otro `409`.
- Un máximo por sucursal y omisión de sucursales vacías.
- Diferencia entre franquicia vacía e inexistente.
- Más de 1 MB de datos, con máximo ubicado en una página posterior.
- Métodos y tipos de contenido incompatibles: `405` y `415`.
- Readiness y documentación estática.

Las unitarias también comprueban el desempate determinista por UUID y la creación diferida hasta suscribirse al publisher.

## Reproducir

Con Docker:

```bash
./mvnw verify -Pintegration
```

Sin Docker, con DynamoDB Local arrancado mediante `scripts/start-local.ps1`:

```powershell
./mvnw.cmd verify -Pintegration "-Ddynamodb.test.endpoint=http://127.0.0.1:8000"
./scripts/smoke.ps1
```

Durante la comprobación de la aplicación ya arrancada se ejecutaron las mismas suites sin reempaquetar el JAR abierto en Windows:

```powershell
./mvnw.cmd test failsafe:integration-test failsafe:verify jacoco:report -Pintegration "-Ddynamodb.test.endpoint=http://127.0.0.1:8000"
```

Los reportes regenerables se encuentran en `target/surefire-reports`, `target/failsafe-reports` y `target/site/jacoco`. No se versionan binarios, bases de datos, caches ni logs.

## Pendiente fuera del alcance local

- Construcción/arranque de Docker y ejecución mediante Testcontainers: Docker no está instalado en el equipo. CI contiene esas comprobaciones, incluido el arranque real de Compose.
- Ejecución del workflow en GitHub: no se publicó el repositorio.
- `terraform plan/apply` con credenciales reales y comprobación contra DynamoDB en AWS: no se desplegaron recursos. La validación de Terraform comprueba configuración y esquema del proveedor, no permisos, cuotas o disponibilidad de una cuenta.
- Publicación en un repositorio público: es necesaria para presentar la prueba conforme a la nota del enunciado; el README incluye los comandos.
