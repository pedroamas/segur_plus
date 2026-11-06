# F5 — Conectar el chat del frontend

**Objetivo:** sustituir el «no disponible» de la pantalla de chat de [F3](F3-frontend.md) por el chat real construido en [F4](F4-dify-y-chat.md). Fase corta: solo toca `frontend/`.

**Entregables**
- Streaming **SSE con `fetch` + `ReadableStream`** (el access token viaja en la cabecera `Authorization`; `EventSource` no sirve). Pasa por el mismo cliente HTTP con refresh y cola de F3; el proxy de Caddy ya tiene `flush_interval -1`.
- **Citas clicables** que abren el documento (visor/preview de F3) solo si el usuario puede leerlo; si el API devuelve 404 se muestra el mensaje, la UI no decide.
- **Lista de conversaciones propias** (`GET /chat/conversations`, `/conversations/{id}/messages`, `DELETE`).
- Estados de error y **timeout** (stream interrumpido, Dify no disponible, 401 con refresh en mitad de la petición).
- La clave de Dify **no** existe en el frontend: el cliente solo habla con `/api/v1/chat`.

**Aceptación (medible)**
1. Una respuesta de chat llega por **streaming** y muestra al menos una **cita válida** en una pregunta documental (criterio 6 original del frontend).
2. Abrir una conversación **ajena** desde la UI (ID de otro usuario) ⇒ 404 y mensaje de no encontrado; no aparece en la lista.
3. E2E en navegador real con el bundle y la red **sin la clave de Dify** (búsqueda automatizada, igual que el criterio 4 de F3).
4. Stream interrumpido y timeout muestran un error recuperable, sin dejar el chat bloqueado.

**Fuera de alcance:** nuevas pantallas, evaluación (F4) y pruebas de seguridad completas (F6).
