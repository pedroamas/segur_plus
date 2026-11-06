# F4 — Dify y chat

**Objetivo:** recuperación documental con permisos y chat con herramientas tipadas, medidos con un conjunto de evaluación desde el principio.

**Entregables**
- Dify 1.18 con Gemini y **embedding fijado desde el inicio**; **4 KBs**: General (empresa + seguros), Direccion, Finanzas, Empleados (un dataset con metadato `owner_id`). Se **verifica en el Swagger de la instancia** la API de `retrieve`, los operadores de filtro de metadatos (¿`contains` en `allowed_roles`? ¿igualdad en `owner_id`?) y la actualización de metadatos; el resultado se anota en el ADR 0003.
- `AiGateway` + adaptador Dify; indexador (evento `DocumentChanged`, estados `PENDING/INDEXING/INDEXED/FAILED`, job de reintento); ingesta con metadatos `doc_id`, `kb`, `classification`, `allowed_roles`, `owner_id`.
- Recuperación: filtro de metadatos (optimización) + **re-autorización de cada fragmento** antes del LLM; fragmento sin mapeo ⇒ descartado.
- Perfil del asistente en YAML (KBs y alcance de herramientas por rol); **4 herramientas** (`get_sales_summary`, `get_commissions`, `get_policies_by_status`, `get_targets`) + `search_documents`. Son fachadas que **reutilizan los servicios de `data` creados en [F3](F3-frontend.md)** (`SalesQuery`, `CommissionQuery`, `PolicyQuery`, `TargetQuery`, con el alcance por rol ya centralizado en `DataScope`); F4 no crea servicios de datos nuevos ni lógica de alcance propia. `employee_id` nunca es parámetro.
- `POST /api/v1/chat` con SSE, citas autorizadas, evento final `verification`; conversaciones privadas (`GET /chat/conversations`, `/conversations/{id}/messages`, `DELETE`); persistencia de tokens y latencia por mensaje; timeouts del ADR 0003.
- Script de evaluación (`evaluation/`): ~30 preguntas etiquetadas (ver [UC §6](../requirements/use-cases.md)), con documentos esperados, documentos prohibidos y comportamiento de rechazo; informa recall@5, citas válidas y fugas.

**Tests obligatorios:** WireMock de Dify devolviendo el corpus completo (solo sobreviven los autorizados); conversación ajena ⇒ 404; `employee_id`/`assistantId` ignorados; stream interrumpido; ejecución contra Dify real.

**Aceptación (medible)**
1. Sobre las ~30 preguntas: **recall@5 ≥ 0,8** y **100 % de citas válidas** (existen, mapean a un documento autorizado y son legibles por el usuario).
2. **Cero** fragmentos o cifras prohibidos en las preguntas negativas, con WireMock (corpus completo) y con Dify real.
3. Las cifras de octubre devueltas por las herramientas coinciden con la BD y con el dashboard de F3 (12 / 9.680 € / 968 € / 80,7 %), porque usan los mismos servicios.
4. Quitar un rol de `allowed_roles` (o cambiar el propietario) deja de mostrar el documento en citas y recuperación sin reindexar.
5. Si el filtro `owner_id` de Dify no funciona, se documenta el plan B (ADR 0003) y la prueba de aislamiento entre empleados sigue en verde.

**Fuera de alcance:** la UI del chat (se conecta en [F5](F5-conectar-chat.md); en F3 la pantalla existe como «no disponible») y el dashboard (ya entregado en F3, con su parte de backend).
