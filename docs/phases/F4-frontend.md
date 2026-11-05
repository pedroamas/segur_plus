# F4 — Frontend

**Objetivo:** cuatro pantallas que muestran el backend sin decidir nada.

**Entregables**
- React + TypeScript + React Router, cliente generado de OpenAPI; el JWT solo en memoria o en el almacenamiento elegido en F1.
- **Login**.
- **Documentos**: listado con filtros, **visor PDF** (pdf.js), **preview de Excel** (HTML del servidor), descarga.
- **Chat**: streaming SSE con `fetch` + `ReadableStream`, citas clicables que abren el documento (si el usuario puede leerlo), lista de conversaciones propias.
- **Dashboard sencillo**: pocas cifras según rol (primas, comisiones, pólizas por estado, cumplimiento de objetivos) con los mismos servicios que las herramientas del chat.
- Fuera: pantallas de arquitectura de IA, de evaluación, analítica aparte y perfil.

**Aceptación (medible)**
1. Los 7 usuarios inician sesión y ven el dashboard de su rol; ninguna cifra ajena (prueba E2E).
2. En cada rol, la lista de documentos del UI coincide con la matriz (7 comprobaciones E2E).
3. PDF y Excel se visualizan; la descarga funciona solo donde la matriz lo permite.
4. El bundle del frontend y las respuestas de red **no contienen** la clave de Dify (búsqueda automatizada).
5. Una respuesta de chat llega por streaming y muestra al menos una cita válida en una pregunta documental.

**Fuera de alcance:** diseño visual elaborado.
