# SegurPlus backend (F1)

Spring Boot 3.5 · Java 21 · PostgreSQL 16 · MinIO. Paquetes: `auth`, `documents`, `data` (F3: servicios de lectura + dashboard), `audit`, `shared` (y esqueleto `ai`).

## Arrancar en local
```bash
cp .env.example deploy/.env             # editar: contraseñas y JWT_SECRET (openssl rand -base64 36)
mkdir -p deploy/data/postgres deploy/data/minio   # antes del primer `up`: si no, Docker los crea como root y MinIO no puede escribir
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d      # PostgreSQL :5434, MinIO :9000/:9001
cd backend
set -a; . ../deploy/.env; set +a        # exporta las variables al proceso
./mvnw spring-boot:run                  # API en http://localhost:8100 (Flyway migra y la semilla carga datos de PRUEBA)
```
- OpenAPI: <http://localhost:8100/swagger-ui.html> · JSON: `/v3/api-docs`.
- Parar: `Ctrl+C` y `docker compose -f deploy/docker-compose.yml --env-file deploy/.env down` (los datos quedan en `deploy/data/`).
- Borrar los datos locales (los crea otro uid): `docker compose ... down && docker run --rm -v "$PWD/deploy":/d postgres:16-alpine rm -rf /d/data`.
- Usuarios semilla: `DIR001` (MANAGER), `ACC001`, `COM001`, `EMP001`-`EMP004`; contraseña = `SEGURPLUS_SEED_DEFAULT_PASSWORD`.
  Con `SEGURPLUS_SEED_DOCUMENTS_SPEC_PATH` informado el backend siembra 32 **fixtures minúsculos** desde `docs/data-model/permissions-spec.yaml` (solo para depurar); por defecto está vacío y los 32 documentos reales los carga `generator` (`python -m segurplus_gen.seed`, ver `generator/README.md`).

## Probar
```bash
cd backend && ./mvnw verify             # requiere Docker (Testcontainers: PostgreSQL y MinIO)
```
Si la sesión no tiene el grupo `docker`: `sg docker -c "./mvnw verify"`. Los tests no usan `deploy/.env`: generan sus propios secretos.

Ejemplo manual (la cookie de refresh es `Secure`: con `curl` sobre http se envía a mano):
```bash
TOKEN=$(curl -s localhost:8100/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"EMP001\",\"password\":\"$SEGURPLUS_SEED_DEFAULT_PASSWORD\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s localhost:8100/api/v1/users/me -H "Authorization: Bearer $TOKEN"
curl -s 'localhost:8100/api/v1/documents?size=5' -H "Authorization: Bearer $TOKEN"
```

## Endpoints (`/api/v1`)
| Método y ruta | Quién | Notas |
|---|---|---|
| `POST /auth/login`, `/auth/refresh`, `/auth/logout` | público (refresh/logout: cookie `segurplus_refresh`) | rotación; reutilización ⇒ 401 y familia revocada |
| `GET /users/me` | autenticado | identidad solo del token |
| `GET /documents` · `/{id}` · `/{id}/view` · `/{id}/preview` · `/{id}/download` | READ/DOWNLOAD por política | no autorizado ⇒ 404; `view` solo PDF, `preview` solo XLSX |
| `GET /dashboard?month=YYYY-MM` | cifras del mes según el **rol del token** (alcance OWN/TEAM/GLOBAL en `DataScopeResolver`); mes por defecto = último mes cerrado del `Clock`; mes sin datos ⇒ 400 | primas, comisiones (devengadas, devoluciones, neto), pólizas por estado, cumplimiento de objetivos; ACCOUNTANT: gastos y sin cumplimiento |
| `POST /documents` (multipart) | solo MANAGER (403 al resto) | tipo real, extensión, ≤10 MB |

## Configuración relevante
`segurplus.auth.access-token-ttl` (10-15 min; falla al arrancar fuera de rango), `segurplus.documents.preview.*` (límites del preview), `segurplus.clock.*` (fecha de negocio 2027-01-15), `segurplus.seed.*` (solo desarrollo).
