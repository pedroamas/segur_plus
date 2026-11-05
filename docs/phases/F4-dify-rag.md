# F4 — Dify y RAG (con evaluación desde el primer día)

**Objetivo:** recuperación documental con permisos, medida desde el principio.

**Entregables**
- Dify 1.18 configurado: modelo Gemini, **embedding fijado desde el inicio** (cambiarlo obliga a reindexar), KBs (Company, Management, Finance, Insurance, Sales, EMP001-004).
- Chunking por tipo: padre-hijo para PDFs largos; tablas/CSV/Excel no se indexan esperando agregaciones (resúmenes narrativos o solo BD). Búsqueda híbrida y reranking si procede (modelo de rerank aparte).
- **Ingesta automatizada por el backend** (Knowledge API): metadatos `owner_id`, `department`, `classification`, `doc_type` asignados al indexar; sincronización de borrados, versiones y cambios de permisos; job de reindexado.
- `AiGateway` + adaptador Dify para recuperación; re-autorización de cada fragmento.
- **Harness de evaluación v0** (RAGAS/DeepEval) y primer golden set (≥ 40 preguntas): tipo, perfil, hechos clave esperados, documentos esperados, documentos **prohibidos**, comportamiento de rechazo.

**Tests obligatorios:** WireMock de Dify en integración; nocturno contra Dify real.

**Aceptación:** recall@5 ≥ 0,8 en el golden set; cero fragmentos prohibidos devueltos; umbral de fidelidad fijado y documentado.

**Fuera de alcance:** chat y herramientas SQL.
