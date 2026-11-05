# F1 — Backend base: esqueleto, seguridad y documentos

**Objetivo:** un backend que autentica, autoriza con la regla simple y sirve documentos desde MinIO, con la matriz de acceso probada desde el primer día.

**Entregables**
- Proyecto Spring Boot (Java 21), paquetes `auth`, `documents`, `data`, `ai`, `audit`, `shared` (solo esqueleto de `ai` y `data`); Maven wrapper; `docker-compose` con PostgreSQL y MinIO; OpenAPI.
- Migraciones Flyway del [esquema](../data-model/schema.md) (12 tablas).
- `auth`: login, access JWT de 10-15 min, **refresh token** ([ADR 0007](../adr/0007-sesiones-jwt-refresh-rotativo.md)): cookie `HttpOnly; Secure; SameSite=Strict`, hash SHA-256 en `refresh_tokens`, rotación en cada uso, `POST /api/v1/auth/refresh` y `POST /api/v1/auth/logout`; `Subject` desde el token, **`AccessPolicyService`** (ADR 0002) con `decide`, `toSpecification` y `authorizeBatch`; deny by default; 404 para lo no autorizado.
- `shared`: `Clock` inyectable (fecha de negocio 2027-01-15), errores RFC 7807, ID de correlación, filtro de logs sin JWT ni contenido.
- `audit`: `AuditPublisher` y registro de DENY, descargas y logins.
- `documents`: listado filtrado en la consulta, detalle, **visor PDF** (entrega inline), **preview Excel** (renderizado en servidor), descarga auditada, subida solo MANAGER (MIME real, tamaño, extensión) a MinIO privado.
- Tests de sesión: **rotación** (cada refresh devuelve un token distinto y deja el anterior revocado con `replaced_by`); **reutilización** de un refresh rotado ⇒ 401 y toda la familia revocada (incluido el token más reciente); **logout** invalida el refresh (siguiente refresh ⇒ 401); **expirado** (reloj de sistema avanzado) ⇒ 401; cookie con `HttpOnly`, `Secure` y `SameSite=Strict`; en BD nunca aparece el token en claro.
- Tests: generación de la matriz usuario × documento × acción desde `permissions-spec.yaml` contra REST (listado, detalle, descarga) y los casos NEG-01, 02, 04, 06, 07, 08, 12; arquitectura (sin ciclos, sin `Instant.now()` fuera de `shared`); Testcontainers de PostgreSQL y MinIO.

**Aceptación (medible)**
1. Matriz REST: **224 celdas × acciones READ/DOWNLOAD** coinciden con el oráculo (0 diferencias); las 42 celdas negativas obligatorias dan 404.
2. Los CHECK de `documents` rechazan: sin MANAGER en `allowed_roles`, `RESTRICTED` con EMPLOYEE, KB-Empleados con EMPLOYEE o sin propietario.
3. Campos `employee_id`/`role` enviados por el cliente no cambian el resultado (test).
4. Cada descarga genera una fila `DOCUMENT_DOWNLOAD`; cada DENY, `ACCESS_DENIED`.
5. Los 4 tests de sesión (rotación, reutilización revoca familia, logout, expirado) pasan; access token ≤ 15 min.
6. `./mvnw verify` verde.

**Fuera de alcance:** datos reales, Dify, chat, frontend.
