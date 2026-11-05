# ADR 0004 — Pipeline de documentos simplificado

Estado: aceptado (revisado en la rama `simple`). **Reemplaza** «Pipeline de tres almacenes» de F0 (versiones, outbox, antivirus, reconciliación completa).

## Contexto
Un documento vive en tres sitios: **MinIO** (original), **PostgreSQL** (metadatos, permisos, estado) y **Dify** (texto troceado y vectores). No hay transacción distribuida. Hay que decidir qué manda y cómo se recupera de fallos parciales. Vistas: [05-document-pipeline](../architecture/05-document-pipeline.md).

## Decisión
**PostgreSQL es la fuente de verdad; MinIO y Dify son derivados reconstruibles.**

1. Orden de alta: validar (MIME real, tamaño, extensión) → SHA-256 → objeto en MinIO → fila en `documents` (`PENDING`) → evento de aplicación `DocumentChanged` → el indexador del paquete `ai` crea el documento en Dify con metadatos.
2. MinIO: bucket privado, clave `documents/<docId>/<sha256>`; solo el backend tiene credenciales; descargas y vistas pasan por el backend y se auditan.
3. Dify: un dataset por KB (4). Metadatos: `doc_id` (UUID de `documents.id`), `kb`, `classification`, `allowed_roles`, `owner_id`.
4. **Estados mínimos** (`documents.index_status`): `PENDING → INDEXING → INDEXED` o `FAILED`; `FAILED → PENDING` por reintento; `INDEXED → PENDING` por reindexado. El borrado es `deleted_at`, aparte.
5. **Sin versiones**: reemplazar un documento es subir uno nuevo (el existente se borra lógicamente). Sin outbox: el evento se procesa tras el commit y un **job de reintento** recoge `PENDING`/`FAILED` antiguos y borrados pendientes en Dify.
6. **Cambios de `allowed_roles`/`owner_id`**: se actualizan en BD (efecto inmediato en el control) y se reenvían como metadatos a Dify; hasta entonces manda la re-autorización.
7. **Borrado**: `deleted_at` inmediato (invisible en listados, citas y recuperación) → borrar en Dify con reintento → purga en MinIO diferida.
8. Fallo cerrado: un fragmento cuyo `document_id` no mapea a un documento vivo se descarta.
9. Sin antivirus en el diseño base (solo el MANAGER sube ficheros; mejora opcional con ClamAV).

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Versiones inmutables + outbox transaccional (versión F0) | Útil en producción; excesivo para 32 documentos subidos por un único rol |
| Dify como fuente de verdad | Pierde permisos, checksum y trazabilidad |
| Indexar de forma síncrona en la subida | Bloquea la petición; Dify tarda segundos o minutos |

## Consecuencias
- (+) Pocas piezas, fáciles de testear (WireMock de Dify, Testcontainers de MinIO).
- (−) Si el proceso cae entre el commit y el evento, el documento queda `PENDING` hasta el job de reintento (aceptable).
- (−) No hay histórico de versiones de un documento.
