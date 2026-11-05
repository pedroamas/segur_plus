# F5 — Integración IA: chat, herramientas y streaming

**Objetivo:** chat con RAG y SQL controlados, con un vertical slice mixto cuanto antes.

**Entregables**
- `AssistantResolver` (JWT → `AssistantContext`). `ai_assistants` como perfiles de configuración.
- Herramientas tipadas (`get_sales_summary`, `get_commissions`, `get_policies`, `compare_periods`, `get_objectives`, `search_documents`) que reutilizan los servicios de analytics; el `employee_id` nunca es parámetro.
- **Vertical slice temprano:** una pregunta mixta (p. ej. comisiones de octubre de un empleado + una cláusula de su contrato).
- `POST /api/v1/chat` con SSE; `GET /chat/conversations`, `/conversations/{id}/messages`, `DELETE`. Conversaciones privadas; `dify_conversation_id` ↔ tabla propia.
- Citas mapeadas a `documents.id` y comprobadas con READ. Verificación de que cifras y citas existen en lo recuperado.
- Errores → RFC 7807; timeouts (3 s / 10 s / 30-60 s / 120 s); reintentos solo antes del primer token; Resilience4j; fallback de modelo; cancelación con parada de Dify.
- Persistencia de tokens, precio calculado, latencia y modelo por mensaje; cuota diaria por usuario.

**Tests obligatorios:** acceso a conversación ajena (404); `employee_id`/`assistantId` ignorados; prompt injection en documentos; routing (corpus etiquetado RAG/SQL/ambas); stream interrumpido.

**Aceptación:** routing ≥ umbral definido en esta fase; 100 % de citas válidas; ninguna fuga en la matriz de acceso cruzado por chat.

**Fuera de alcance:** UI definitiva.
