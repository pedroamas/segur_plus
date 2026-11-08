# SegurPlus frontend (F3)

React 19 · TypeScript · Vite · React Router · pdf.js. UI en español. Pantallas: Login, Panel (dashboard por rol), Documentos (listado, visor PDF, preview de Excel, descarga y **subida solo para MANAGER**) y Chat (F5: streaming SSE, citas, verificación, conversaciones propias).

La UI **no decide permisos**: solo muestra lo que devuelve el API (404/403 ⇒ mensaje). El access JWT vive **solo en memoria**; la renovación usa la cookie HttpOnly `segurplus_refresh` (`src/api/client.ts`: un único refresh en vuelo, reintento único, cola para las peticiones concurrentes).

## Desarrollo
```
cd frontend
npm install                      # solo la primera vez
npm run dev                      # Vite en :5174; /api se proxifica a http://localhost:8100
npm test                         # Vitest (jsdom en los tests de componentes): cliente HTTP, subida, parser SSE, streaming y pantalla de chat (74 tests)
npm run build                    # tsc + vite build → frontend/dist
npm run gen:api                  # regenera src/api/schema.d.ts desde http://localhost:8100/v3/api-docs (backend arrancado)
```
`src/api/types.ts` da nombre a los tipos generados. El API de listado solo filtra por `kb`, `category` y `fileType`: **no** hay búsqueda por título ni filtro por clasificación en el servidor, y la UI no los simula.

## Servir por HTTPS en la LAN (contenedor Caddy `web`)
`https://192.168.0.28:5174`, **solo LAN** (el puerto se publica en `WEB_BIND_IP`, no en `0.0.0.0`; **no abrir el 5174 en el router**). Caddy (`deploy/Caddyfile`, `tls internal`) sirve `frontend/dist` y hace proxy de `/api/*` a `host.docker.internal:8100` (mismo origen, sin CORS, `flush_interval -1` para el SSE de F5). El navegador avisará por el certificado propio: se acepta una vez (la CA se conserva en `deploy/data/caddy`).
```
# reconstruir el front y que Caddy lo sirva (dist se monta como volumen: basta con reconstruir)
cd frontend && npm run build
# arrancar / reiniciar / parar solo el contenedor web (desde la raíz del repo)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d web
docker compose -f deploy/docker-compose.yml --env-file deploy/.env restart web
docker compose -f deploy/docker-compose.yml --env-file deploy/.env stop web
# tras cambiar deploy/Caddyfile
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --force-recreate web
curl -k -I https://192.168.0.28:5174/
```
Si cambia la IP de la LAN, define `WEB_BIND_IP` en `deploy/.env`. El backend debe estar corriendo en el host (:8100), p. ej. `set -a; . deploy/.env; set +a; java -jar backend/target/segurplus-backend-0.1.0-SNAPSHOT.jar`.

## E2E (Playwright + Chrome del sistema, contra el stack vivo)
```
cd frontend && npm run build && npm run test:e2e
```
Requiere postgres, minio, el backend en :8100 y el contenedor `web` arriba, con los datos reales de F2 (`generator`). Lee la contraseña de desarrollo de `deploy/.env` en tiempo de ejecución (no se guarda ni se imprime) y compara contra dos oráculos independientes del backend: `docs/data-model/permissions-spec.yaml` (documentos) y consultas SQL directas al contenedor de PostgreSQL (cifras). Dashboard de los 7 usuarios, lista de documentos por rol, visores y descarga, ausencia de claves en `dist/` y en la red (valores de `deploy/.env` + patrones), renovación y logout, subida y chat (F5). En total 53 pruebas.

## Subida de documentos (F3b, solo MANAGER)
El botón «Subir documento» (pantalla Documentos) solo se pinta si el rol del perfil es `MANAGER`; es solo comodidad visual: la autoridad es el backend (un no-MANAGER recibe 403 en `POST /documents`, `GET /users` y `GET /documents/upload-config`). El diálogo (`<dialog>` nativo: foco atrapado, Esc, sin estilos ni scripts en línea, compatible con la CSP) pide archivo, código, título, KB, categoría, clasificación, propietario opcional (selector alimentado por `GET /api/v1/users`) y roles permitidos (MANAGER siempre incluido por el servidor). Formatos y tamaño máximo salen de `GET /api/v1/documents/upload-config` (`.pdf .xlsx .csv .json`, 10 MB). El cliente valida archivo, obligatorios, formato del código y tamaño; **no** valida las combinaciones clasificación/roles/propietario ni el tipo real: eso lo decide el servidor y su `ProblemDetail` se muestra siempre (409 código duplicado, 415 tipo, 413 tamaño, 422 combinación). Un envío a la vez (botón y cancelar deshabilitados mientras sube). Tras subir, el listado se recarga con los filtros ajustados a la KB/categoría/tipo del nuevo documento y un aviso enlaza a él.

El E2E `e2e/upload.spec.ts` (8 pruebas) sube documentos con código `TE2E*` y los **borra al terminar** (antes y después de la suite): fila en `documents`, objeto en MinIO (DELETE firmado SigV4 con las credenciales de `deploy/.env`, sin imprimirlas) y filas de `audit_logs` de ese documento. Si el proceso se mata a mitad, la siguiente ejecución limpia los restos.

## Chat (F5)
Pantalla `/chat` (y `/chat/:conversationId`), solo habla con `/api/v1/chat`: la clave de Dify/Gemini **no existe** en el frontend (el E2E de secretos lo comprueba en `dist/` y en las respuestas de red, incluido el SSE).
- **Streaming**: `src/api/chat.ts` (`streamChat`) hace `POST /chat` con `fetch` + `ReadableStream` por el mismo cliente HTTP de F3 (`api.postStream`: token en `Authorization`, un 401 renueva con la cola de refresh y reintenta **antes** de abrir el stream). `src/api/sse.ts` es un parser incremental (eventos partidos entre chunks, CRLF/CR, comentarios). Eventos: `conversation`, `status`, `tool`, `delta`, `sources`, `verification`, `done`, `error`; los desconocidos se ignoran.
- **Render**: el texto del modelo es **texto**: párrafos, listas, **negrita** y `código` se construyen como elementos de React (nunca `dangerouslySetInnerHTML` ni markdown→HTML; HTML, imágenes y enlaces markdown se ven literales). Sin estilos ni scripts en línea (CSP de Caddy intacta). `[D04]` solo es enlace si el servidor lo devolvió en `sources` (autorizado); los enlaces abren `/documentos/:id` (visor/preview de F3): si el API da 404 se muestra «Documento no encontrado.», la UI no decide.
- **Verificación**: línea discreta bajo la respuesta (✓ cifras respaldadas / ⚠ cifras o citas no respaldadas, con la lista). Tokens y latencia solo al terminar cada respuesta (también al releer el historial).
- **Conversaciones**: lista de las propias (`GET /chat/conversations`), abrir (`/conversations/{id}/messages`), borrar con confirmación en línea (`DELETE`) y «Nueva conversación». Una ajena por URL ⇒ 404 ⇒ «Conversación no encontrada.».
- **Errores**: 429/503 (HTTP o evento `error`) ⇒ «límite de uso del modelo… Reintenta en unos minutos»; 504/`ai-timeout` ⇒ «ha tardado demasiado»; stream cortado sin `done` ⇒ «se interrumpió» (conserva el texto parcial, marcado incompleto). **Timeout por inactividad de 90 s** sin ningún dato (`IDLE_TIMEOUT_MS`). En cualquier error la pregunta vuelve al cuadro y el chat sigue usable. **Cancelar** (`AbortController`) corta la petición; el backend marca el mensaje `CANCELLED`. Sin doble envío (guarda síncrona + cuadro de solo lectura + botón deshabilitado).
- **Teclado y accesibilidad**: Enter envía, Shift+Enter nueva línea; el historial es un `role="log"` con `aria-live="polite"` y `aria-busy` mientras genera; `role="status"` con la fase («Buscando en los documentos…»); el foco queda en el cuadro; límite de 2000 caracteres como el servidor.

### E2E del chat y presupuesto del modelo
`e2e/chat.spec.ts`: 5 pruebas **reales** en serie (2 turnos reales al modelo: DIR001 «ventas de octubre» y EMP001 «¿qué cubre el seguro de hogar?»; cada turno gasta 2 peticiones a Gemini: planificador + generador) y 11 con `POST /chat` interceptado (`page.route`, 0 llamadas a Dify/Gemini): 429, 503, error SSE, timeout del modelo, stream cortado, 401+refresh, Enter/Shift+Enter/doble envío, cancelar, XSS con CSP real, cita a documento no legible y verificación fallida. Las conversaciones creadas se borran de la BD por diferencia con una foto previa (`cleanupE2eConversations`: mensajes, auditoría y conversación; las 55 de la evaluación F4 no se tocan). **Ejecuta la suite completa con cuidado**: cada pasada gasta 2 turnos reales (~4 peticiones) de la cuota gratuita diaria; para iterar usa `npx playwright test e2e/chat.spec.ts -g interceptado`.
