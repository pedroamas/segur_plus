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

## Estado (rama `f5-chat-front`)
Implementada solo en `frontend/` (detalle en [frontend/README.md](../../frontend/README.md#chat-f5) y [DECISIONES](../DECISIONES.md#f5--chat-en-el-frontend-rama-f5-chat-front)).

| Criterio | Resultado |
|---|---|
| 1. Streaming con al menos una cita válida | E2E real EMP001 «¿Qué cubre el seguro de hogar?»: fuentes con D27 (citada), cita `[D27]` clicable que abre el documento (`doc-code` = D27) |
| 2. Conversación ajena ⇒ 404 y mensaje | E2E: EMP001 abre por URL la de DIR001 ⇒ `GET …/messages` = 404 ⇒ «Conversación no encontrada.»; la lista de EMP001 coincide con sus filas en BD y no contiene la de DIR001 |
| 3. Sin la clave de Dify en bundle ni red | `secrets.spec.ts` (ahora con las claves del planificador y de reserva) + escaneo de las respuestas del chat real (SSE incluido): 0 coincidencias |
| 4. Stream interrumpido y timeout recuperables | E2E interceptado (stream cortado, `ai-timeout`, 429, 503, error SSE) y Vitest (timeout por inactividad de 90 s con reloj simulado): mensaje claro, pregunta recuperada, chat libre |

Pruebas: Vitest **74** (38 nuevas: parser SSE, cliente de streaming, pantalla de chat); Playwright **53** (48 anteriores/nuevas con intercept + 5 del chat real). Llamadas reales al modelo: **2 turnos** (≈4 peticiones a Gemini) en la pasada completa del chat real. Limitaciones: el timeout de 90 s y la cancelación del stream real solo se prueban en Vitest / con intercept (no se esperan 90 s en E2E); el 429 real de Gemini no se provoca (se simula).
