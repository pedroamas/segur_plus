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
Paquetes: `auth` (login, JWT, `AccessPolicyService`), `documents`, `ai` (AiGateway, indexador, chat), `data` (customers, policies, commissions, invoices, expenses, sales_targets), `audit`, `shared` (Clock, errores, Money).

Reglas simples: dependencias sin ciclos (`ai → documents, data, auth, audit`; `documents`/`data → auth, audit`); un paquete solo usa los **servicios públicos** de otro; sin Spring Modulith.

## Reglas que nunca se rompen
- Autorización **siempre en el backend**, con `AccessPolicyService` (deny by default). Nada de decisiones en el frontend, en el prompt ni en Dify.
- Regla de documentos: READ/DOWNLOAD si `rol ∈ documents.allowed_roles` o `owner_id = usuario`; CREATE/UPDATE/DELETE/ADMINISTER solo MANAGER.
- La identidad sale del JWT. Ignorar `employee_id`, `role`, `assistantId`, `datasetId` enviados por el cliente.
- Recurso no autorizado → 404. Listados filtrados en la consulta (Specification), no en memoria.
- Cada fragmento recuperado de Dify se **re-autoriza** antes de enviarlo al LLM.
- El LLM no ejecuta SQL libre; solo las 4 herramientas tipadas, que reutilizan los servicios de `data`; `employee_id` nunca es parámetro.
- `users.monthly_salary` no sale en ningún DTO, endpoint ni herramienta.
- No registrar JWT, prompts, respuestas ni contenido de fragmentos. Logs con ID de correlación.
- Secretos en `.env` (no versionado). La clave de Dify solo en el backend.
- IDs expuestos: UUID. Importes: `NUMERIC(14,2)` + moneda. Errores: RFC 7807. API bajo `/api/v1`.
- «Hoy» sale del `Clock` inyectable (2027-01-15); nada de `Instant.now()`/`LocalDate.now()` fuera de `shared` (excepción: expiración del JWT en `auth`).

## Cómo trabajar
- Cambios pequeños. Cada funcionalidad entra con sus tests, **incluidos los de autorización**.
- Decisión relevante → ADR en `docs/adr/NNNN-titulo.md` (contexto, decisión, alternativas, consecuencias).
- Preguntar antes de cambiar el alcance, el modelo de permisos o un supuesto del brief.
- Mantener la simplicidad: no añadir tablas, paquetes ni herramientas fuera del alcance sin preguntar.

## Comandos (completar al crear el proyecto)
```
./mvnw verify                  # tests backend
docker compose up -d           # stack local (postgres, minio, dify, backend, frontend)
npm run dev | build | test     # frontend
```

## Definición de hecho
Código + tests (unitarios, integración y autorización) + OpenAPI actualizada + ADR si hubo decisión + CI verde.
