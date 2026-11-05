# F7 — Frontend

**Objetivo:** intranet profesional, responsive, sin lógica de autorización.

**Entregables**
- Login, dashboard por perfil, biblioteca documental (filtros, búsqueda de metadatos), visor PDF (pdf.js) y Excel (JSON saneado del servidor), chat con streaming (`fetch` + `ReadableStream`) y panel de fuentes/herramientas, perfil de usuario.
- **AI Architecture** y **RAG Evaluation**: solo para MANAGER/administración.
- Cliente generado desde OpenAPI. Token en memoria, refresh por cookie HttpOnly. La UI puede ocultar botones según capacidades de `/users/me`, pero nunca autoriza.

**Tests:** componentes y flujos principales (login, descarga, chat); sin la clave de Dify en el bundle.

**Aceptación:** cada rol ve solo su navegación; sanitización de contenido renderizado; accesibilidad básica.
