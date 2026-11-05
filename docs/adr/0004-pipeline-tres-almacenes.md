# ADR 0004 — Pipeline de tres almacenes y máquina de estados de indexación

Estado: propuesto (F0)

## Contexto
Un documento vive en tres sitios: **MinIO** (original), **PostgreSQL** (metadatos, permisos, estado) y **Dify** (texto troceado y vectores). No hay transacción distribuida entre ellos. Hay que decidir qué manda, cómo se propagan versiones, borrados y cambios de permisos, y cómo se recupera de fallos parciales. Vista de diagramas: [05-document-pipeline](../architecture/05-document-pipeline.md).

## Decisión
**PostgreSQL es la fuente de verdad; MinIO y Dify son derivados reconstruibles.** Orden: guardar binario inmutable → registrar en PostgreSQL → propagar a Dify mediante **outbox transaccional** (`outbox_events`, entrega at-least-once, consumidores idempotentes).

### Almacenes y claves
- MinIO: bucket privado; clave `documents/<docId>/v<n>/<sha256>` (inmutable; una versión nueva es un objeto nuevo). Solo el backend tiene credenciales; las descargas pasan por el backend y se auditan.
- PostgreSQL: `documents` (estado actual, `current_version_id`, `indexed_version_id`, ids de Dify, `checksum_sha256`), `document_versions` (inmutables), `document_permissions`.
- Dify: un dataset por KB (9). Metadatos en cada documento: `doc_id` (UUID de `documents.id`), `kb`, `classification`, `category`, `owner_employee_id`, `version`.

### Máquina de estados (`documents.index_status`)
Estados del brief: `PENDING → INDEXING → INDEXED / FAILED`.

| Desde | Evento | Hasta | Acción |
|---|---|---|---|
| (alta) | subida válida y escaneo limpio | PENDING | outbox `DOCUMENT_INDEX_REQUESTED` |
| PENDING | el worker toma el evento | INDEXING | enviar a Dify; guardar `dify_document_id` |
| INDEXING | Dify completa | INDEXED | `indexed_version_id = current_version_id`, `indexed_at` |
| INDEXING | error o agotados los reintentos | FAILED | `index_error_code`; auditar |
| FAILED | reintento (backoff exponencial, máx. N) o acción manual | PENDING | — |
| INDEXED | nueva versión, cambio de KB o reindexado | PENDING | `DOCUMENT_REINDEX_REQUESTED` |
| INDEXED | cambio de metadatos (clasificación, categoría, propietario) | INDEXED | `dify_metadata_synced=false` + `DOCUMENT_METADATA_CHANGED` |
| cualquiera | borrado | — | ver «Borrados» |

El resultado del escaneo (`scan_status`), el borrado (`deleted_at`) y la sincronía de metadatos son **ejes aparte**: una versión `INFECTED` o con escaneo pendiente nunca se indexa. `ck_documents_indexed` impide marcar `INDEXED` sin `dify_document_id`, `indexed_version_id` e `indexed_at`.

### Versiones
Nueva versión = fila en `document_versions` + objeto nuevo en MinIO + `current_version_id`. Se conservan las anteriores (auditoría; purga por retención). En Dify solo existe la versión vigente (actualizar por fichero conserva el id); mientras `indexed_version_id <> current_version_id` el documento está desactualizado y el reconciliador lo devuelve a `PENDING`. Los fragmentos devueltos con `version` distinta de la vigente se descartan o se marcan como obsoletos (decisión de F4).

### Borrados (orden fijo)
1. **Soft delete en PostgreSQL** (`deleted_at`, `deleted_by_user_id`): desaparece al instante de listados, citas y recuperación (la re-autorización lo ve borrado).
2. Outbox `DOCUMENT_DELETE_REQUESTED`: borrar en Dify con reintentos hasta confirmación.
3. El reconciliador confirma la ausencia en Dify.
4. Purga de MinIO tras el periodo de retención (`DOCUMENT_PURGE_REQUESTED`, `purged_at`).
Un fragmento de un documento borrado o sin mapeo se descarta aunque Dify aún lo devuelva (fallo cerrado).

### Cambios de permisos
- **Grants** (alta, baja, caducidad, cambio de rol/departamento): **no se propagan a Dify** porque no viven allí; se aplican en la re-autorización por consulta. Efecto inmediato y sin reindexar (criterio de la matriz: «usuario retirado de un grant»).
- **Atributos** que sí viajan como metadatos (clasificación, categoría, propietario): se actualizan en Dify de forma asíncrona. Mientras tanto la re-autorización manda; el filtro de Dify queda como cota (puede ser menos o más estricto transitoriamente, nunca decide).
- **Cambio de KB**: borrar en el dataset origen y crear en el destino (nuevo `dify_document_id`); los datasets tienen su `dify_dataset_id` en `knowledge_bases`.

### Integridad y reconciliación
- `checksum_sha256` (SHA-256 del binario) en la versión y copia en `documents`; verificable contra MinIO. Subidas con el mismo checksum en el mismo documento no crean versión nueva.
- Job de reconciliación periódico (y manual): huérfanos en Dify (borrar), `INDEXED` ausentes en Dify (→ `PENDING`), versiones desfasadas (→ `PENDING`), metadatos sin sincronizar (reenviar). Deja resumen en `audit_logs`.
- Idempotencia: el id del outbox viaja como clave; antes de crear en Dify se busca por metadato `doc_id` para evitar duplicados tras un fallo entre «crear en Dify» y «guardar `dify_document_id`».
- Subida: validar MIME real, tamaño y extensión; escanear antes de indexar; contenido = dato no confiable.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Dify (o MinIO) como fuente de verdad | No ofrece consulta relacional ni permisos; reindexar desde un derivado no es posible |
| Escritura síncrona a los tres almacenes | Fallo parcial sin compensación y latencia en la subida |
| Sincronizar grants a Dify como metadatos | Duplica la política y crea una ventana de inconsistencia en el control de acceso |
| CDC (Debezium) en lugar de outbox | Más infraestructura para el mismo resultado |
| Saga con orquestador externo | Desproporcionado; la máquina de estados + reconciliador basta |

## Consecuencias
- (+) El control de acceso es consistente al instante; las caídas de Dify solo retrasan la búsqueda, no la seguridad.
- (+) Cualquier almacén derivado se reconstruye desde PostgreSQL + MinIO.
- (−) Consistencia eventual en la búsqueda: un documento nuevo no es recuperable hasta `INDEXED`; la UI muestra el estado.
- (−) Hay que operar outbox, reconciliador y retención. Se mitiga con métricas por estado y `ix_documents_pipeline`.
- La API de Dify para actualizar por fichero y para metadatos debe verificarse en su Swagger (no verificado en F0).
