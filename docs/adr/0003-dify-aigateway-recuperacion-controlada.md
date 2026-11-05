# ADR 0003 — Dify detrás de `AiGateway`, con recuperación controlada por el backend

Estado: propuesto (F0)

## Contexto
Dify 1.18 ofrece Knowledge API (`/datasets/...`, incluida la recuperación) y apps de chat (`/chat-messages`) sobre Gemini. Podría recuperar y generar dentro de un chatflow, pero con streaming el backend **no puede retirar** lo ya emitido: es detección, no prevención. Los principios 1, 5, 6, 9 y 12 del brief exigen que el backend decida y que el contenido recuperado se trate como dato no confiable.

## Decisión
1. **Puerto `ai.AiGateway`** con adaptador Dify (`WebClient` + Resilience4j). Operaciones: `plan(...)`, `retrieve(datasetIds, query, filter, topK)`, `generate(context, history, stream)`, `indexDocument(...)`, `updateMetadata(...)`, `deleteDocument(...)`, `indexingStatus(...)`. El resto del sistema solo conoce este puerto; cambiarlo (p. ej. Spring AI + pgvector) no toca dominio.
2. **Recuperación controlada por el backend**: el backend llama a la Knowledge API de Dify con filtro de metadatos (optimización) y **re-autoriza cada fragmento** con `AccessPolicyService` antes de que llegue al LLM (ADR 0002). La app de generación de Dify **no tiene nodo de conocimiento**: recibe solo el contexto ya autorizado.
3. **Contexto no confiable**: los fragmentos y resultados de herramientas se envían delimitados y marcados como datos; el LLM no tiene herramientas de escritura; el sistema no ejecuta instrucciones halladas en documentos (criterio 9).
4. **Dos llamadas por turno** (H-10): planificación (una app de Dify sin conocimiento que devuelve JSON con herramientas y parámetros tipados, validado contra `ai_tools`) y generación (`chat-messages`). Se suman sus tokens en el mensaje.
5. **Identidad hacia Dify**: `user` = `users.dify_user_ref` (`u_<uuid>` opaco, estable, distinto de `users.id`; sin email ni nombre). Aísla historiales de Dify; **no autoriza**.
6. **Conversaciones**: `conversations` propia es la fuente de verdad y el propietario se comprueba ahí; `dify_conversation_id` es solo el vínculo. Una conversación ajena ⇒ 404 sin consultar a Dify.
7. **Claves**: dos distintas (app y Knowledge) más la de Gemini, solo en el backend/gestor de secretos; `ai_assistants.dify_app_alias` guarda un alias, no la clave.
8. **Citas**: `metadata.retriever_resources` → mapear `document_id` de Dify a `documents.id` y comprobar READ de nuevo antes de enviar la cita; guardar en `message_sources` (referencias, no texto).
9. **Costes**: `metadata.usage` → tokens por mensaje; el precio lo calcula SegurPlus con `ai_model_prices` vigente; cuota diaria por usuario (fecha de negocio).
10. **Fallos**: conexión 3 s, primera respuesta 10 s, inactividad 30-60 s, tope 120 s; reintentos solo antes del primer token; circuit breaker y bulkhead por destino; modelo de reserva configurable (Gemini devuelve 503 por alta demanda). Si el stream solo emite `ping`: revisar worker/Celery/Redis.
11. **Verificación y SSE**: ver H-11; por defecto, respuestas con cifras de SQL se verifican antes de emitirse.

Variante A (recuperar dentro del chatflow con filtros por `inputs`): **rechazada por defecto**; solo con decisión explícita y su propio ADR.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Chatflow con recuperación interna (Variante A) | No hay prevención con streaming; el control queda en Dify |
| Un app/dataset de Dify por usuario | Duplica prompts y configuración; contradice A6 |
| Spring AI + pgvector directamente | Quita Dify del stack y de la demostración; el puerto lo deja abierto |
| Llamar a Gemini directo desde el backend | Reimplementa troceado, indexación y recuperación; se pierde el valor de Dify |

## Consecuencias
- (+) El LLM solo ve texto autorizado: aunque el filtro de Dify falle, no hay fuga. El fallo cerrado (fragmento sin mapeo ⇒ descartar) protege frente a desincronización de índices.
- (+) Testeable con WireMock: se puede devolver el corpus entero y comprobar que sobrevive solo lo autorizado.
- (−) Latencia extra por la llamada de planificación y la re-autorización (una consulta por lote).
- (−) Acoplamiento a la forma de la API de Dify (parámetros de `retrieve`, filtros de metadatos): **no verificado en F0** (se comprueba en el Swagger de la instancia en F4).
- (−) El filtro de Dify puede devolver menos de `topK` útiles tras descartar; se pide un `topK` mayor y se recorta tras autorizar.
