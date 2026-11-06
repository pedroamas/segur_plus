# SegurPlus — convenciones del repositorio

Contexto: `docs/PROJECT_BRIEF.md`. Política de acceso: `docs/security/permissions-matrix.md`. Fases: `docs/phases/` (F1-F5). Decisiones y recortes: `docs/DECISIONES.md`.

## Stack
Java 21, Spring Boot, Spring Security (JWT), Spring Data JPA, Flyway, WebClient (Dify), OpenAPI. React + TypeScript + React Router. PostgreSQL, MinIO, Dify (1.18), Gemini. Docker Compose. JUnit, Mockito, Spring Boot Test, Testcontainers (Postgres, MinIO), WireMock (Dify). ArchUnit opcional.

## Estructura de paquetes (por funcionalidad)
```
com.segurplus.<paquete>/
  api/   controladores REST + DTOs (nunca exponer entidades)
  ...    servicios, entidades y repositorios (repositorios no públicos)
```
Paquetes: `auth` (login, JWT + refresh, `AccessPolicyService`), `documents`, `ai` (AiGateway, indexador, chat), `data` (customers, policies, commissions, invoices, expenses, sales_targets), `audit`, `shared` (Clock, errores, Money).

Reglas simples: dependencias sin ciclos (`ai → documents, data, auth, audit`; `documents`/`data → auth, audit`); un paquete solo usa los **servicios públicos** de otro; sin Spring Modulith.

## Reglas que nunca se rompen
- Autorización **siempre en el backend**, con `AccessPolicyService` (deny by default). Nada de decisiones en el frontend, en el prompt ni en Dify.
- Regla de documentos: READ/DOWNLOAD si `rol ∈ documents.allowed_roles` o `owner_id = usuario`; CREATE/UPDATE/DELETE/ADMINISTER solo MANAGER.
- La identidad sale del JWT. Ignorar `employee_id`, `role`, `assistantId`, `datasetId` enviados por el cliente.
- Sesión (ADR 0007): access token de 10-15 min; refresh opaco en cookie `HttpOnly; Secure; SameSite=Strict`, con **rotación en cada uso**, solo su hash SHA-256 en BD y **reutilización ⇒ se revoca toda la familia**. Endpoints `POST /api/v1/auth/refresh` y `/auth/logout`. Nunca registrar tokens ni cookies.
- Recurso no autorizado → 404. Listados filtrados en la consulta (Specification), no en memoria.
- Cada fragmento recuperado de Dify se **re-autoriza** antes de enviarlo al LLM.
- El LLM no ejecuta SQL libre; solo las 4 herramientas tipadas, que reutilizan los servicios de `data`; `employee_id` nunca es parámetro.
- `users.monthly_salary` no sale en ningún DTO, endpoint ni herramienta.
- No registrar JWT, prompts, respuestas ni contenido de fragmentos. Logs con ID de correlación.
- Secretos en `.env` (no versionado). La clave de Dify solo en el backend.
- IDs expuestos: UUID. Importes: `NUMERIC(14,2)` + moneda. Errores: RFC 7807. API bajo `/api/v1`.
- «Hoy» sale del `Clock` inyectable (2027-01-15); nada de `Instant.now()`/`LocalDate.now()` fuera de `shared` (excepción: expiración del JWT y del refresh en `auth`, tiempo real).

## Cómo trabajar
- Cambios pequeños. Cada funcionalidad entra con sus tests, **incluidos los de autorización**.
- Decisión relevante → ADR en `docs/adr/NNNN-titulo.md` (contexto, decisión, alternativas, consecuencias).
- Preguntar antes de cambiar el alcance, el modelo de permisos o un supuesto del brief.
- Mantener la simplicidad: no añadir tablas, paquetes ni herramientas fuera del alcance sin preguntar.

## Comandos
```
# infraestructura local (PostgreSQL :5434, MinIO :9000/:9001); secretos en deploy/.env (copiar de .env.example)
mkdir -p deploy/data/postgres deploy/data/minio        # solo la primera vez
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down

# backend (desde backend/; API en :8100, OpenAPI en /swagger-ui.html)
set -a; . ../deploy/.env; set +a && ./mvnw spring-boot:run
./mvnw verify                  # tests: JUnit, Testcontainers (PostgreSQL y MinIO), ArchUnit; requiere Docker (si hace falta: sg docker -c "./mvnw verify")
./mvnw test -Dtest=PermissionsMatrixTest                # matriz usuario x documento (224 celdas) contra el oráculo del YAML

# datos y documentos (F2; desde generator/, con el backend arrancado al menos una vez)
uv venv .venv && . .venv/bin/activate && uv pip install -r requirements.txt     # solo la primera vez
python -m segurplus_gen.seed   # resetea e inserta el dataset, genera los 32 documentos, los sube a MinIO y rellena documents (idempotente)
python -m pytest               # anclas de octubre, cifra documento = BD, determinismo, matriz de 224 celdas contra :8100

npm run dev | build | test     # frontend (F4)
```
Detalle: `backend/README.md` y `generator/README.md`.

## Definición de hecho
Código + tests (unitarios, integración y autorización) + OpenAPI actualizada + ADR si hubo decisión + CI verde.
