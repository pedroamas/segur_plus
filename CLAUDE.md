# SegurPlus — convenciones del repositorio

Contexto del proyecto: `docs/PROJECT_BRIEF.md`. Política de acceso: `docs/security/permissions-matrix.md`. Fases: `docs/phases/`.

## Stack
Java 21, Spring Boot, Spring Security (JWT), Spring Data JPA, Flyway, WebClient (Dify), OpenAPI. React + TypeScript + React Router. PostgreSQL, MinIO, Dify (1.18), Gemini. Docker Compose. JUnit, Mockito, Spring Boot Test, Testcontainers (Postgres, MinIO), WireMock (Dify), Spring Modulith/ArchUnit.

## Estructura de paquetes (monolito modular)
```
com.segurplus.<modulo>/
  api/             controladores REST + DTOs (nunca exponer entidades)
  application/     casos de uso / servicios
  domain/          entidades, reglas
  infrastructure/  repositorios, clientes externos (Dify, MinIO)
```
Módulos: `auth, users, departments, documents, security, ai, analytics, sales, customers, insurance, finance, hr, audit, shared`.
Un módulo solo usa la API pública de otro (paquete raíz o `api`), nunca sus `infrastructure`. Se verifica en tests.

## Reglas que nunca se rompen
- Autorización **siempre en el backend**, con `AccessPolicyService`. Nada de decisiones en el frontend, en el prompt ni en Dify.
- La identidad sale del JWT. Ignorar `employee_id`, `role`, `assistantId`, `datasetId` enviados por el cliente.
- Deny by default. Recurso no autorizado → 404. Listados filtrados en la consulta (Specification), no en memoria.
- El LLM no ejecuta SQL libre; solo herramientas tipadas que reutilizan los servicios de `analytics`.
- No registrar JWT, prompts, respuestas ni contenido de fragmentos. Logs estructurados con ID de correlación.
- Secretos en `.env` (no versionado) o gestor de secretos. Nunca en el repo ni en el frontend.
- IDs expuestos: UUID. Importes: `NUMERIC(14,2)` + moneda. Errores: RFC 7807. API bajo `/api/v1`.
- Subida de archivos: validar MIME real, tamaño y extensión; escanear antes de indexar.

## Cómo trabajar
- Cambios pequeños. Cada funcionalidad entra con sus tests, **incluidos los de autorización**.
- Decisión relevante → ADR en `docs/adr/NNNN-titulo.md` (contexto, decisión, alternativas, consecuencias).
- Preguntar antes de cambiar el alcance, el modelo de permisos o un supuesto del brief.
- Si algo del brief no cuadra con la realidad, decirlo; no corregirlo en silencio.

## Comandos (completar al crear el proyecto)
```
./mvnw verify                  # tests backend
docker compose up -d           # stack local (postgres, minio, dify, backend, frontend)
npm run dev | build | test     # frontend
```

## Definición de hecho
Código + tests (unitarios, integración y autorización) + OpenAPI actualizada + ADR si hubo decisión + sin warnings de módulos + CI verde.
