# SegurPlus frontend (F3)

React 19 · TypeScript · Vite · React Router · pdf.js. UI en español. Pantallas: Login, Panel (dashboard por rol), Documentos (listado, visor PDF, preview de Excel, descarga) y Chat (**«no disponible»**: no hace ninguna petición hasta F5).

La UI **no decide permisos**: solo muestra lo que devuelve el API (404/403 ⇒ mensaje). El access JWT vive **solo en memoria**; la renovación usa la cookie HttpOnly `segurplus_refresh` (`src/api/client.ts`: un único refresh en vuelo, reintento único, cola para las peticiones concurrentes).

## Desarrollo
```
cd frontend
npm install                      # solo la primera vez
npm run dev                      # Vite en :5174; /api se proxifica a http://localhost:8100
npm test                         # Vitest: cliente HTTP, refresh, cola y 401 (11 tests)
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
Requiere postgres, minio, el backend en :8100 y el contenedor `web` arriba, con los datos reales de F2 (`generator`). Lee la contraseña de desarrollo de `deploy/.env` en tiempo de ejecución (no se guarda ni se imprime) y compara contra dos oráculos independientes del backend: `docs/data-model/permissions-spec.yaml` (documentos) y consultas SQL directas al contenedor de PostgreSQL (cifras). 32 pruebas: dashboard de los 7 usuarios, lista de documentos por rol, visores y descarga, ausencia de claves en `dist/` y en la red (valores de `deploy/.env` + patrones), renovación y logout, y chat no disponible.
