# ADR 0003 — Dify detrás de `AiGateway`, con recuperación controlada por el backend

Estado: aceptado (revisado en la rama `simple`: un asistente, 4 KBs, sin precios ni cuota). Mantiene la decisión de F0 y la simplifica.

## Contexto
Dify 1.18 ofrece Knowledge API (`/datasets/...`, incluida la recuperación) y apps de chat (`/chat-messages`) sobre Gemini. Podría recuperar y generar dentro de un chatflow, pero con streaming el backend **no puede retirar** lo ya emitido: es detección, no prevención. El backend debe decidir y el contenido recuperado es dato no confiable.

## Decisión
1. **Puerto `ai.AiGateway`** con adaptador Dify (`WebClient`). Operaciones: `plan`, `retrieve(datasetIds, query, filter, topK)`, `generate(context, history)`, `indexDocument`, `updateMetadata`, `deleteDocument`, `indexingStatus`. Cambiar Dify (p. ej. Spring AI + pgvector) no toca el resto.
2. **Cuatro bases de conocimiento**: KB-General (empresa + seguros), KB-Direccion, KB-Finanzas, KB-Empleados (un único dataset con metadato `owner_id`).
3. **Un solo asistente**: el perfil (KBs por rol, alcance de herramientas) está en configuración YAML. El backend deriva del rol del JWT qué KBs consulta; el cliente no elige KB ni asistente.
4. **Recuperación controlada**: el backend llama a la Knowledge API con filtro de metadatos (`allowed_roles`/`owner_id`; optimización) y **re-autoriza cada fragmento** con `AccessPolicyService` antes de que llegue al LLM. La app de generación no tiene nodo de conocimiento: recibe solo contexto ya autorizado.
5. **Contexto no confiable**: fragmentos y resultados de herramientas se envían delimitados y marcados como datos; el LLM no tiene herramientas de escritura.
6. **Dos llamadas por turno**: planificación (app sin conocimiento que devuelve JSON con herramientas y parámetros tipados, validado contra el catálogo de 4) y generación (`chat-messages`).
7. **Identidad hacia Dify**: `user` = `users.dify_user_ref` (`u_<uuid>` opaco). Aísla historiales; **no autoriza**.
8. **Conversaciones**: la tabla propia es la fuente de verdad; ajena ⇒ 404 sin consultar a Dify.
9. **Claves**: la de app y la de Knowledge solo en el backend; nunca en el frontend.
10. **Citas**: `metadata.retriever_resources` → `documents.id` → comprobar READ de nuevo antes de enviarlas; se guardan referencias en `messages.sources`.
11. **Métricas**: `metadata.usage` → `tokens_in`/`tokens_out` y latencia por mensaje. Sin precios, sin cuota.
12. **Fallos**: timeouts 3 s / 10 s / 30 s / 120 s; un reintento solo antes del primer token. Resilience4j y modelo de reserva quedan como mejora opcional.
13. **Sin reranking ni búsqueda híbrida obligatorios** (mejora opcional si la evaluación lo justifica).

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Chatflow con recuperación interna | No hay prevención con streaming; el control queda en Dify |
| Un dataset por empleado (versión F0) | 4 datasets extra para lo que resuelve un filtro `owner_id` + re-autorización |
| Spring AI + pgvector | Quita Dify del stack y de la demostración; el puerto lo deja abierto |
| Gemini directo desde el backend | Reimplementa troceado, indexación y recuperación |

## Consecuencias
- (+) El LLM solo ve texto autorizado: aunque el filtro de Dify falle, no hay fuga (fallo cerrado).
- (+) Testeable con WireMock devolviendo el corpus entero.
- (−) Latencia extra por la planificación y la re-autorización (una consulta por lote).
- (−) Con un único dataset de empleados, si el filtro `owner_id` no funciona en Dify 1.18, la recuperación devuelve fragmentos ajenos que se descartan y puede quedar sin resultados útiles (**riesgo de recall, no de fuga**). Se comprueba en F4; plan B: pedir `topK` mayor o un dataset por empleado.
- (−) Acoplamiento a la forma de la API de Dify: verificada en F4 contra la instancia real (ver «Verificación en Dify 1.18»).

## Verificación en Dify 1.18 (F4, instancia real v1.18.0)
Comprobado en el código del contenedor `api` y con llamadas reales (sonda con 3 documentos de prueba, ya borrados):
- **Recuperación**: `POST /v1/datasets/{id}/retrieve` con `{query (≤250 car.), retrieval_model:{search_method, reranking_enable, top_k, score_threshold_enabled, metadata_filtering_conditions:{logical_operator, conditions:[{name, comparison_operator, value}]}}}`. Responde `records[].segment{id, content, document{id, name, doc_metadata}}` y `score`. El `doc_metadata` viene como objeto nombre → valor.
- **Operadores** disponibles: `contains`, `not contains`, `start with`, `end with`, `is`, `is not`, `empty`, `not empty`, `in`, `not in`, `=`, `≠`, `>`, `<`, `≥`, `≤`, `before`, `after`. `contains` es un `LIKE %valor%` **sobre texto**, por eso `allowed_roles` se guarda como `,MANAGER,ACCOUNTANT,` y se filtra con `,ROL,` (sin delimitadores, `MANAGER` casaría con `SALES_MANAGER`).
- **Resultados de la sonda**: `contains ,EMPLOYEE,` devolvió solo el documento con EMPLOYEE; `contains ,MANAGER,` los tres; `contains ,SALES_MANAGER,` ninguno; `owner_id is <valor>` solo el del propietario; `logical_operator: or` (rol **o** propietario) funciona; tras actualizar los metadatos de un documento (quitar ACCOUNTANT) dejó de salir **sin reindexar**.
- **Metadatos**: los campos se crean por dataset (`POST /v1/datasets/{id}/metadata {type:"string", name}`) y se asignan con `POST /v1/datasets/{id}/documents/metadata {operation_data:[{document_id, metadata_list:[{id,name,value}]}]}` (`partial_update` disponible). Todos de tipo `string`; `owner_id` vale `-` si no hay propietario.
- **Ingesta**: `create-by-file` de Dify **no admite JSON** (extensiones: txt, md, pdf, html, xlsx, xls, docx, csv…), por eso el backend extrae el texto (PDFBox / POI / CSV / JSON) y usa `document/create-by-text` (`high_quality`, `text_model`, separador `\n\n`, 700 tokens, solape 70). Estado: `GET /v1/datasets/{id}/documents/{docId}` → `indexing_status` (`waiting`, `parsing`, `cleaning`, `splitting`, `indexing`, `completed`, `error`). Borrado: `DELETE /v1/datasets/{id}/documents/{docId}` (204).
- **Apps de chat**: `POST /v1/chat-messages` en `blocking` (planificador) y `streaming` (eventos `message`, `ping`, `message_end` con `metadata.usage`, `error`). El backend **no** usa `conversation_id` de Dify: reenvía el historial propio, de modo que un cambio de permisos no deja contexto viejo en Dify.
- **Plan B de `owner_id`**: no hizo falta; el filtro por propietario funciona. Si dejara de funcionar, la re-autorización ya garantiza el aislamiento y solo caería el recall.
- **Incidencia de la instancia**: Weaviate rechazaba la clave de API de Dify (401) porque `WEAVIATE_AUTHENTICATION_APIKEY_ALLOWED_KEYS` no coincidía con `WEAVIATE_API_KEY` en `~/dify/docker/.env` (rotación a medias). Se alineó la clave permitida con la ya rotada (copia previa en `.env.bak-f4`) y se recrearon `weaviate`, `api` y `worker`. La credencial de Gemini guardada en Dify tenía una clave distinta: el aprovisionamiento la actualiza con la de `deploy/.env`.
- La clave Knowledge es **del workspace**: ve también datasets ajenos a SegurPlus; el adaptador solo usa los 4 por nombre exacto.
