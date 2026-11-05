# F0 — Diseño y decisiones

**Objetivo:** dejar por escrito lo que condiciona todo lo demás, antes de escribir código.

**Entregables**
- Confirmación o cambio de los supuestos A1-A6 del brief.
- Diagrama de arquitectura (contexto, contenedores, componentes) y diagrama del flujo de chat.
- **ERD** completo (PostgreSQL): usuarios/roles/permisos, empleados, departamentos, documentos (+ versiones, clasificaciones, `document_permissions` como grants con `subject_type/subject_id/action`, `owner_employee_id`, `dify_dataset_id`, `dify_document_id`, estado de indexación, checksum), conversaciones y mensajes (fuentes, tokens, modelo, latencia), `ai_assistants` (perfil de configuración), `ai_evaluations`, CRM, seguros, finanzas, **HR** (nóminas, contratos, objetivos), `audit_logs` (append-only, particionada por mes).
- Matriz de permisos validada (`docs/security/permissions-matrix.md`).
- Casos de uso y requisitos.
- ADR 0001-0005: monolito modular (Modulith), ABAC con servicio único de política, Dify con `AiGateway`, pipeline de tres almacenes, herramientas SQL tipadas.

**Aceptación:** supuestos confirmados; ERD y matriz sin contradicciones con el brief; cada tabla con PK, FK, índices y restricciones; importes `NUMERIC(14,2)`.

**Fuera de alcance:** código.
