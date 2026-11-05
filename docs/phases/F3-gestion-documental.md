# F3 — Gestión documental

**Objetivo:** documentos originales en MinIO con acceso estrictamente controlado.

**Entregables**
- Integración MinIO (bucket privado) y `POST /api/v1/documents` con validación de MIME real, tamaño, extensión y escaneo.
- Metadatos, versiones y estado de indexación (`PENDING → INDEXING → INDEXED / FAILED`); checksum SHA-256.
- `GET /documents` (paginado, filtros por departamento/categoría/formato/fecha/propietario/clasificación), `GET /documents/{id}`, `/preview` (PDF en streaming; Excel renderizado en servidor con fórmulas calculadas y límites), `/download` (streaming + auditoría).
- Listados, conteos y facetas filtrados por política. 404 para no autorizados.

**Tests obligatorios:** descarga y preview ajenos denegados; acceso directo a MinIO imposible; IDs modificados; archivos maliciosos rechazados; XSS al renderizar.

**Aceptación:** matriz de F1 extendida a preview/download pasa al 100 %; ninguna URL pública a objetos.

**Fuera de alcance:** sincronización con Dify (F4).
