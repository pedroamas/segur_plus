# deploy

Despliegue local de SegurPlus con Docker Compose (LAN; nada se publica en internet ni en el router).

## Contenido

| Servicio | Imagen | Puerto por defecto | Notas |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | `127.0.0.1:5434` | datos en `${DATA_DIR}/postgres` |
| `minio` | Chainguard (digest fijado) | `127.0.0.1:9000` (API), `:9001` (consola) | datos en `${DATA_DIR}/minio` |
| `backend` | `backend/Dockerfile` (Maven + Temurin 21, usuario no root) | `127.0.0.1:8100` | healthcheck: `GET /v3/api-docs` (no hay Actuator) |
| `web` | `frontend/Dockerfile` (Vite + Caddy) | `WEB_BIND_IP:5174` (HTTPS) | proxifica `/api/*` a `BACKEND_UPSTREAM` |
| `seed` (perfil `seed`) | `generator/Dockerfile` | — | **un solo uso; RESETEA datos** |
| `mockdify` (perfil `mockdify`) | `mock-dify/Dockerfile` | — (sin publicar) | Dify **simulado**, solo pruebas |

**Dify no está en el compose**: es una instancia aparte (`~/dify/docker`, `DIFY_BASE_URL`, por defecto `http://192.168.0.28:8088`, sin `/v1`).

## Arrancar la instancia completa (clone limpio)

```bash
cp .env.example deploy/.env && chmod 600 deploy/.env     # editar: contraseñas, JWT_SECRET, LOCAL_UID/LOCAL_GID (ver .env.example)
mkdir -p deploy/data/postgres deploy/data/minio deploy/data/caddy   # antes del primer up (si no, Docker los crea como root)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile seed run --rm seed   # datos + 32 documentos (RESETEA)
scripts/check-dify.sh                                    # comprueba la dependencia de Dify
```
Entonces `https://<WEB_BIND_IP>:5174` (certificado propio de Caddy: el navegador avisa una vez). Usuarios: `DIR001`, `ACC001`, `COM001`, `EMP001`-`EMP004`; clave = `SEGURPLUS_SEED_DEFAULT_PASSWORD`. El seed necesita el backend arrancado (crea las tablas con Flyway y los 7 usuarios): `run seed` lo levanta si falta. Tras el seed, el indexador (cada 30 s) envía los documentos a Dify; hace falta Dify con las claves de `.env`.

Parar: `docker compose -f deploy/docker-compose.yml --env-file deploy/.env down` (los datos quedan en `DATA_DIR`). Borrar todo: `down -v` y, para `DATA_DIR`, `docker run --rm -v "$PWD/deploy":/d --entrypoint rm postgres:16-alpine -rf /d/data` (PostgreSQL crea sus ficheros con otro uid).

## Dify

Primera vez (instancia nueva): `python3 deploy/dify/provision_dify.py` (idempotente: proveedor Gemini, 4 datasets `SegurPlus General/Direccion/Finanzas/Empleados`, 4 apps y claves en `deploy/.env`; credenciales del admin en `~/.config/segurplus/dify_admin.env`). Gemini en capa gratuita: ~20 peticiones al día; no lanzar la evaluación ni los E2E de chat sin necesidad. Si Dify no está accesible, el backend arranca igual, pero el chat responde 503 y el indexador deja los documentos en `PENDING`/`FAILED` (reintento a los 5 min). `scripts/check-dify.sh` lo diagnostica.

### Dify simulado (perfil `mockdify`)

> **Solo para pruebas y demos.** No hay modelo ni embeddings: la «respuesta» es un texto fijo que cita códigos `[Dxx]` del contexto, y el retrieve ordena por palabras en común e **ignora los filtros de metadatos** (el peor caso para el backend). No sirve para evaluar calidad.

En `.env`: `DIFY_BASE_URL=http://mockdify:8080` y cualquier valor no vacío en `DIFY_KNOWLEDGE_API_KEY`, `DIFY_APP_API_KEY` y `DIFY_PLANNER_API_KEY`. Arranque: `docker compose ... --profile mockdify up -d --build`. Implementa los endpoints de la API 1.18 que usa el backend (datasets, metadatos, `create-by-text`, estado, borrado, `retrieve` y `chat-messages` en blocking y streaming); el estado vive en el volumen `mockdify-state`.

## Perfiles, proyecto y datos paralelos

- `seed` y `mockdify` **no** arrancan con `up` a secas. Para `down` de todo: añadir `--profile seed --profile mockdify`.
- Para levantar una **copia** sin tocar la instancia actual: otro `.env` con `COMPOSE_PROJECT_NAME`, `DATA_DIR` y puertos propios (reservarlos antes en el Port Panel). `scripts/clone-test.sh` hace exactamente esto y lo desmonta.

## Instancia con el backend como proceso del host (`java -jar`)

La instancia de desarrollo mantiene PostgreSQL, MinIO y Caddy en contenedores y el backend en el host (`:8100`), con las imágenes originales (`caddy:2-alpine` + `frontend/dist` montado). Con los valores por defecto el recurso resuelto de `postgres` y `minio` es idéntico al anterior; `web` solo añade `BACKEND_UPSTREAM`:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.host-backend.yml --env-file deploy/.env up -d postgres minio web
```
Hay que nombrar `postgres minio web`: sin nombrarlos, `up` arrancaría también el contenedor `backend` (puerto 8100 ocupado por el del host). La superposición fija `BACKEND_UPSTREAM=host.docker.internal:8100` y monta `frontend/dist` (`cd frontend && npm run build`). El `seed` en esta modalidad se ejecuta con el venv del generador (`generator/README.md`), no con el servicio del compose (que levantaría el contenedor backend).

## Puertos y variables

Todos los puertos son variables (`POSTGRES_PORT`, `MINIO_API_PORT`, `MINIO_CONSOLE_PORT`, `BACKEND_PORT`, `WEB_PORT`, `WEB_BIND_IP`); los valores por defecto son los de la instancia actual. En este servidor se reservan en el Port Panel antes de cambiarlos. Lista completa y comentada en `../.env.example`. `deploy/.env` y `deploy/data/` no se versionan.

## Ficheros

`docker-compose.yml` (compose completo) · `docker-compose.host-backend.yml` (superposición para el backend en el host) · `Caddyfile` (HTTPS interno, cabeceras de seguridad, proxy `/api`, SPA) · `mock-dify/` (Dify simulado) · `dify/` (aprovisionamiento de Dify real).
