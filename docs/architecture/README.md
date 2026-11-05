# Arquitectura de SegurPlus

Estado: F0 (diseño). Sin código. Diagramas en Mermaid.

| Documento | Contenido |
|---|---|
| [01-context.md](01-context.md) | Contexto del sistema (actores y sistemas externos) |
| [02-containers.md](02-containers.md) | Contenedores (React, backend, PostgreSQL, MinIO, Dify, Gemini) |
| [03-components.md](03-components.md) | Módulos Spring Modulith, dependencias permitidas, propiedad de tablas |
| [04-chat-flow.md](04-chat-flow.md) | Flujo del chat (8 pasos del brief), fallos y plazos |
| [05-document-pipeline.md](05-document-pipeline.md) | Tres almacenes (MinIO, PostgreSQL, Dify): subida, indexación, borrado |

Decisiones: [`../adr/`](../adr/). Modelo de datos: [`../data-model/`](../data-model/). Requisitos: [`../requirements/use-cases.md`](../requirements/use-cases.md).
Hallazgos y huecos del brief/matriz: [`../F0-hallazgos.md`](../F0-hallazgos.md).

## Principios que condicionan el diseño
1. Toda autorización vive en el backend y pasa por **un único** `AccessPolicyService` (REST, herramientas de IA, recuperación RAG).
2. El filtro de Dify es una optimización; el control es la **re-autorización de cada fragmento** en el backend (ADR 0003).
3. El LLM no ejecuta SQL libre ni escribe nada: solo herramientas tipadas de lectura (ADR 0005).
4. PostgreSQL es la fuente de verdad; MinIO y Dify son derivados reconstruibles (ADR 0004).
5. Hay dos relojes: el **reloj de negocio** (fecha ficticia 2027-01-15) y el **reloj de sistema** (seguridad y operación) (ADR 0006).
