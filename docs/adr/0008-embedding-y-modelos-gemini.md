# ADR 0008 — Embedding y modelos de chat: Gemini vía Dify

Estado: aceptado (F4).

## Contexto
El embedding se **fija al crear los datasets**: cambiarlo obliga a reindexar todo. Había dos opciones: Gemini vía Dify o Ollama local (`http://192.168.0.28:11434`, hoy caído, con modelo de chat `qwen3.5:4b`). El propietario decidió usar Gemini para el chat; los documentos son ficticios, así que enviar texto a la API de Google es aceptable.

## Decisión
1. **Embedding: `gemini-embedding-001`** (plugin `langgenius/gemini` de Dify, proveedor `langgenius/gemini/google`). Estable, 3.072 dimensiones, contexto de 2.048 tokens (los fragmentos se cortan en 700 tokens con solape de 70). Verificado contra la API de Google (ListModels y `embedContent`, HTTP 200).
2. **Chat: `gemini-3-flash-preview`** (principal) y **`gemini-3.5-flash`** (reserva), verificados contra la API de Google. Se configuran con `GEMINI_MODEL` y `GEMINI_MODEL_FALLBACK` al ejecutar `deploy/dify/provision_dify.py`. Una app de Dify está ligada a un solo modelo, así que hay **4 apps**: planificador y generador, cada una con su versión de reserva; el backend reintenta **una vez, solo antes del primer token**, contra la app de reserva (ADR 0003 §12). Alternativa disponible en el plugin: `gemini-3.8-flash` (no respondió en la comprobación de hoy). `gemini-3.5-flash` dio 503 por demanda alta en ocasiones anteriores, de ahí que sea la reserva y no el principal.
3. Ollama queda descartado para F4 (caído y más débil); no hay dependencia de él.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Embedding con Ollama local | Nada sale de la máquina, pero hoy está caído y Dify tendría que apuntar a una URL de la LAN; mayor riesgo operativo |
| `gemini-embedding-2-preview` | Preview; el estable es preferible para fijar el índice |

## Cómo reindexar si se cambia el embedding (o el troceado)
Dify no permite cambiar el modelo de embedding de un dataset con documentos. Procedimiento:
1. Cambiar `EMBEDDING_MODEL` en `deploy/.env` (y habilitar el modelo en Dify).
2. Borrar los 4 datasets «SegurPlus …» en la consola de Dify (o por la Knowledge API) y volver a ejecutar `python3 deploy/dify/provision_dify.py`, que los recrea con el nuevo embedding.
3. Marcar los documentos para reindexar: `UPDATE documents SET index_status='PENDING', dify_dataset_id=NULL, dify_document_id=NULL, indexed_at=NULL;`. El job del indexador (cada 30 s) los reindexa desde MinIO, que es la fuente del original.
4. Reiniciar el backend para vaciar la caché de IDs de datasets/metadatos y repetir `evaluation/run_eval.py`.

## Consecuencias
- (+) Un único proveedor y una sola clave (`GEMINI_API_KEY`, solo en `deploy/.env`).
- (−) La cuota gratuita de embedding de Gemini devuelve **429** al indexar los 32 documentos de golpe. El indexador lo trata como FAILED y reintenta a los 5 min (observado en la primera carga).
- (−) Los textos de los documentos salen a la API de Google durante la indexación y las preguntas (ficticios).
