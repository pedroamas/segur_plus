# scripts

Utilidades de integración y despliegue. Todo es local (LAN): nada abre puertos fuera de la máquina ni llama a Gemini.

| Script | Para qué |
|---|---|
| `ci-local.sh [paso...]` | Los mismos pasos que `.github/workflows/ci.yml` (el workflow llama a este script paso a paso) |
| `clone-test.sh [--keep]` | Prueba de **clone limpio**: clona, levanta, siembra, verifica la checklist y desmonta |
| `check-dify.sh [--strict]` | Comprueba que la instancia de Dify responde, que la clave de Knowledge vale y que existen los 4 datasets |
| `scan-secrets.sh` | Escaneo de secretos sobre `git ls-files` (solo imprime `fichero:línea`, nunca el valor) |
| `clone_checks.py` | Checklist funcional contra un despliegue (lo usa `clone-test.sh`) |

## ci-local.sh

```bash
scripts/ci-local.sh              # todo, en orden; se detiene en el primer fallo y da un resumen con tiempos
scripts/ci-local.sh frontend     # solo algunos pasos: backend generator frontend compose secrets
```

| Paso | Qué hace |
|---|---|
| `backend` | `./mvnw verify`: JUnit, ArchUnit y Testcontainers (PostgreSQL y MinIO). Requiere Docker; si la sesión no tiene el grupo `docker` usa `sg docker -c`. Reintenta una vez (puertos/contenedores). Tarda minutos |
| `generator` | venv temporal, `pip install -r requirements.txt`, importación del paquete y recolección de pytest. **No ejecuta pytest**: los 43 tests necesitan la BD sembrada (y los `live`, backend y MinIO); se corren a mano contra el stack vivo (`generator/README.md`). El seed completo se prueba en `clone-test.sh` |
| `frontend` | En una **copia temporal** (no toca `frontend/node_modules` ni `frontend/dist`, que sirve el Caddy vivo): `npm ci`, `tsc --noEmit`, `npm run build` y Vitest. **No** ejecuta Playwright: los E2E reales gastan turnos de Gemini |
| `compose` | `docker compose config -q` con un `.env` derivado de `.env.example`: perfiles por defecto, `seed`+`mockdify` y la superposición `host-backend`; comprueba además que `up` sin perfiles no incluye `seed` ni `mockdify` |
| `secrets` | `scan-secrets.sh`: patrones (`AIza…`, `sk-…`, `app-…`, claves privadas, AWS, GitHub) y los valores de `deploy/.env` (si existe) en los ficheros versionados |

En GitHub Actions cada paso es un trabajo independiente (`java 21`, `node 22`, `python 3.12`). El workflow no se ejecuta en este repositorio (sin GitHub).

## clone-test.sh

Reproduce la aceptación 3 de F6 (`docker compose up` desde un clone limpio + seed). No toca la instancia viva:

1. `git clone` de la rama actual **tal como está commiteada** en un directorio temporal (`mktemp -d`).
2. Reserva 5 puertos libres en el Port Panel (backend, postgres, MinIO API/consola, web), uno por categoría.
3. Genera un `.env` con secretos aleatorios (sin imprimirlos), proyecto `segurplus-clone`, `DATA_DIR` propio, web en `127.0.0.1` y el Dify **simulado** (perfil `mockdify`).
4. `docker compose up -d --build` y espera la salud del backend; `--profile seed run --rm seed`; espera a que el indexador deje los 32 documentos en `INDEXED`.
5. Checklist (`clone_checks.py`, a través del Caddy): 7 logins, rol de `/users/me`, una pregunta de chat por rol (MANAGER, ACCOUNTANT, SALES_MANAGER, EMPLOYEE) con SSE hasta `done`, y acceso cruzado (EMP001 pide el documento de EMP002 ⇒ 404; control positivo con el propio).
6. Desmonta (`down -v --rmi local`, borra el temporal con un contenedor porque PostgreSQL crea sus ficheros con otro uid, libera los puertos) y comprueba que `segurplus-web-1`, `segurplus-postgres-1` y `segurplus-minio-1` siguen igual (mismo id y arranque, mismos puertos, `deploy/data` sin cambios).

Imprime un informe `OK`/`FALLO` por punto y sale con código distinto de 0 si algo falla. La primera ejecución descarga Maven y Node (varios minutos); después usa la caché de Docker (~4 min). Con `--keep` no desmonta (para depurar; hay que liberar a mano puertos y proyecto). Requiere acceso a Docker y al Port Panel (`PORTCTL_PYTHON`/`PORTCTL` para otras rutas).

Con el Dify simulado la prueba **no valida la calidad de las respuestas** (eso lo hace `evaluation/run_eval.py` contra el Dify real): valida el cableado de extremo a extremo (indexación, recuperación, re-autorización, SSE) sin gastar modelo.

## check-dify.sh

```bash
scripts/check-dify.sh            # avisa y sale con 0 aunque falle (no rompe arranques)
scripts/check-dify.sh --strict   # sale con 1 si Dify no está operativo
```
Lee `DIFY_BASE_URL` y `DIFY_KNOWLEDGE_API_KEY` del entorno o de `deploy/.env` (`--env-file` para otro). Solo hace GET; no imprime claves.
