# SegurPlus

Portfolio de seguridad y autorización con RAG con permisos. Empresa ficticia de seguros (corredor), 7 usuarios, un asistente de IA que solo ve lo que el usuario puede leer. **Versión simplificada** (rama `simple`): 12 tablas, 5 paquetes, 4 bases de conocimiento, 4 pantallas, 5 fases. Estado: solo diseño, sin código.

| Fichero | Para qué |
|---|---|
| [`docs/PROJECT_BRIEF.md`](docs/PROJECT_BRIEF.md) | Alcance, supuestos, principios de seguridad, arquitectura, datos y 8 criterios de aceptación |
| [`CLAUDE.md`](CLAUDE.md) | Convenciones del repo para Claude Code |
| [`docs/security/permissions-matrix.md`](docs/security/permissions-matrix.md) | Regla de acceso, documentos y casos negativos |
| [`docs/architecture/`](docs/architecture/README.md) | Contexto, contenedores, componentes, flujo del chat, pipeline de documentos |
| [`docs/data-model/`](docs/data-model/schema.md) | Esquema (12 tablas), ERD, mapeo de permisos y especificación YAML |
| [`docs/requirements/use-cases.md`](docs/requirements/use-cases.md) | Requisitos y casos de uso |
| [`docs/adr/`](docs/adr/) | Decisiones (ADR 0001-0007) |
| [`docs/phases/`](docs/phases/) | F1 backend base · F2 datos y documentos · F3 Dify y chat · F4 frontend · F5 seguridad y portfolio |
| [`docs/DECISIONES.md`](docs/DECISIONES.md) | Qué se recortó, por qué y qué queda pendiente |

Cómo usarlo: en cada fase pide a Claude Code «ejecuta `docs/phases/F<n>-….md` respetando `CLAUDE.md` y el brief». El diseño completo anterior (54 tablas, 9 KBs, 10 fases) sigue en la rama `f0-diseno`.
