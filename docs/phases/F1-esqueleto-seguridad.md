# F1 — Esqueleto backend, esquema y seguridad

**Objetivo:** proyecto Spring Boot con esquema versionado y autorización funcionando **antes** de cualquier funcionalidad.

**Entregables**
- Proyecto Java 21 con estructura de módulos y verificación Modulith/ArchUnit.
- Docker Compose mínimo (Postgres, MinIO).
- Migraciones Flyway del esquema de F0 y datos semilla de usuarios/roles/departamentos.
- JWT (acceso 10-15 min, refresh con rotación en cookie HttpOnly), login con límite de intentos, `GET /api/v1/users/me`.
- `AccessPolicyService` (ABAC, deny by default), `PermissionEvaluator`, Specifications para listados, auditoría de accesos.
- OpenAPI con esquema de seguridad. Errores RFC 7807.

**Tests obligatorios**
- Matriz usuario × documento × acción **generada desde la política** (parametrizada).
- JWT manipulado, expirado, `alg: none`; mass assignment (`role` en el cuerpo); IDOR por ID.

**Aceptación:** 100 % de la matriz pasa; un usuario no puede alterar su rol ni identidad por HTTP; CI verde.

**Fuera de alcance:** documentos reales, IA, frontend.
