# SegurPlus — paquete de especificación v2

| Fichero | Para qué |
|---|---|
| `docs/PROJECT_BRIEF.md` | Contexto estable: alcance, supuestos A1-A6, principios de seguridad, arquitectura, datos, criterios de aceptación |
| `CLAUDE.md` | Convenciones del repo para Claude Code (raíz del repositorio) |
| `docs/security/permissions-matrix.md` | Política de acceso por documento, asistente y caso de prueba |
| `docs/phases/F0…F9` | Un prompt por fase, con entregables y aceptación medibles |

Cómo usarlo: copia el contenido a la raíz del repo; en cada fase pide a Claude Code "ejecuta `docs/phases/F<n>-…md` respetando `CLAUDE.md` y el brief". Antes de F0, confirma o corrige los supuestos A1-A6 del brief.

Orden de fases (cambiado respecto al original): diseño → esqueleto con seguridad → datos → documentos → Dify/RAG con evaluación → integración IA → analytics → frontend → endurecimiento → portfolio. La seguridad y la evaluación entran desde F1 y F4, no al final.
