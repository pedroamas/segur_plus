# Arquitectura de SegurPlus

Diagramas en Mermaid. Versión simplificada (rama `simple`): un backend con 5 paquetes, un asistente, 4 KBs.

| Documento | Contenido |
|---|---|
| [01-context.md](01-context.md) | Contexto del sistema (actores y sistemas externos) |
| [02-containers.md](02-containers.md) | Contenedores (React, backend, PostgreSQL, MinIO, Dify, Gemini) |
| [03-components.md](03-components.md) | Paquetes del backend, dependencias permitidas, propiedad de tablas |
| [04-chat-flow.md](04-chat-flow.md) | Flujo del chat: recuperación controlada, herramientas, SSE |
| [05-document-pipeline.md](05-document-pipeline.md) | Subida, indexación y borrado de documentos (estados mínimos) |

Decisiones: [`../adr/`](../adr/). Modelo de datos: [`../data-model/`](../data-model/). Requisitos: [`../requirements/use-cases.md`](../requirements/use-cases.md). Qué se recortó y por qué: [`../DECISIONES.md`](../DECISIONES.md).

## Principios que condicionan el diseño
1. Toda autorización vive en el backend y pasa por **un único** `AccessPolicyService` (REST, herramientas, recuperación RAG).
2. El filtro de Dify es una optimización; el control es la **re-autorización de cada fragmento** en el backend (ADR 0003).
3. El LLM no ejecuta SQL libre ni escribe nada: solo herramientas tipadas de lectura (ADR 0005).
4. PostgreSQL es la fuente de verdad; MinIO y Dify son derivados reconstruibles (ADR 0004).
5. El «hoy» es una fecha ficticia (2027-01-15) que sale de un `Clock` inyectable (ADR 0006).
