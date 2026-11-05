# F1 — Backend base: esqueleto, seguridad y documentos

**Objetivo:** un backend que autentica, autoriza con la regla simple y sirve documentos desde MinIO, con la matriz de acceso probada desde el primer día.

**Entregables**
- Proyecto Spring Boot (Java 21), paquetes `auth`, `documents`, `data`, `ai`, `audit`, `shared` (solo esqueleto de `ai` y `data`); Maven wrapper; `docker-compose` con PostgreSQL y MinIO; OpenAPI.
- Migraciones Flyway del [esquema](../data-model/schema.md) (11 tablas).
- `auth`: login, JWT de acceso, `Subject` desde el token, **`AccessPolicyService`** (ADR 0002) con `decide`, `toSpecification` y `authorizeBatch`; deny by default; 404 para lo no autorizado.
- `shared`: `Clock` inyectable (fecha de negocio 2027-01-15), errores RFC 7807, ID de correlación, filtro de logs sin JWT ni contenido.
- `audit`: `AuditPublisher` y registro de DENY, descargas y logins.
- `documents`: listado filtrado en la consulta, detalle, **visor PDF** (entrega inline), **preview Excel** (renderizado en servidor), descarga auditada, subida solo MANAGER (MIME real, tamaño, extensión) a MinIO privado.
- Tests: generación de la matriz usuario × documento × acción desde `permissions-spec.yaml` contra REST (listado, detalle, descarga) y los casos NEG-01, 02, 04, 06, 07, 08, 12; arquitectura (sin ciclos, sin `Instant.now()` fuera de `shared`); Testcontainers de PostgreSQL y MinIO.

**Aceptación (medible)**
1. Matriz REST: **224 celdas × acciones READ/DOWNLOAD** coinciden con el oráculo (0 diferencias); las 42 celdas negativas obligatorias dan 404.
2. Los CHECK de `documents` rechazan: sin MANAGER en `allowed_roles`, `RESTRICTED` con EMPLOYEE, KB-Empleados con EMPLOYEE o sin propietario.
3. Campos `employee_id`/`role` enviados por el cliente no cambian el resultado (test).
4. Cada descarga genera una fila `DOCUMENT_DOWNLOAD`; cada DENY, `ACCESS_DENIED`.
5. `./mvnw verify` verde.

**Fuera de alcance:** datos reales, Dify, chat, frontend.
