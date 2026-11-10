#!/usr/bin/env bash
# Prueba de "clone limpio" (F6, aceptación 3): clona el repo local en un directorio temporal, lo levanta con Docker Compose en un proyecto
# propio (puertos reservados en el Port Panel, datos propios, secretos aleatorios, Dify SIMULADO), siembra los datos, verifica la
# checklist (7 logins, rol de /users/me, 1 pregunta de chat por rol con SSE hasta `done`, acceso cruzado EMP001->EMP002 = 404),
# lo desmonta y comprueba que la instancia viva de SegurPlus no ha cambiado. No hace NINGUNA llamada a Gemini (el Dify es el mock).
#
# Uso: scripts/clone-test.sh [--keep]     (--keep: no desmonta; para depurar. Luego: borrar a mano con `docker compose ... down -v`)
# Se clona la rama ACTUAL tal como está commiteada (lo no commiteado no se prueba). Nada de lo impreso es un secreto.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${PORTCTL_PYTHON:-/home/lucas/.ductor/workspace/venv/bin/python}"
CTL="${PORTCTL:-/home/lucas/.ductor/workspace/ports_registry/portctl.py}"
PROJECT="${CLONE_PROJECT:-segurplus-clone}"
KEEP=0; [ "${1:-}" = "--keep" ] && KEEP=1
[ "$PROJECT" != "segurplus" ] || { echo "El proyecto de la prueba no puede llamarse 'segurplus' (es la instancia viva)." >&2; exit 2; }
unset COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES   # el entorno del shell no debe colarse en la prueba
docker info >/dev/null 2>&1 || { echo "No hay acceso a Docker (¿grupo docker?). Pruebe: sg docker -c $0" >&2; exit 2; }

REPORT=(); RESERVED=(); TMP=""; STARTED=0; T0=$SECONDS
rep() { REPORT+=("$1  $2"); printf '[%s] %s\n' "$1" "$2"; }
ok_or_fail() { if [ "$1" = 0 ]; then rep "OK   " "$2"; else rep "FALLO" "$2"; fi; }
log() { printf '\n== %s (%ss)\n' "$1" "$((SECONDS - T0))"; }

live_state() {   # estado de la instancia viva: contenedores (id, arranque, puertos) y listado de deploy/data (sin leer su contenido)
  for c in segurplus-web-1 segurplus-postgres-1 segurplus-minio-1; do
    docker inspect -f '{{.Name}} {{.Id}} {{.State.Status}} {{.State.StartedAt}}' "$c" 2>&1
  done
  docker ps --filter 'name=^segurplus-(web|postgres|minio)-1$' --format '{{.Names}} {{.Ports}}' | sort
  for d in "" /caddy /minio /postgres; do ls -ld --time-style=full-iso "$ROOT/deploy/data$d" 2>&1; done
  ls -la --time-style=full-iso "$ROOT/deploy/data" "$ROOT/deploy/data/caddy" "$ROOT/deploy/data/minio" 2>&1
}

reserve_port() {   # reserve_port NOMBRE CATEGORIA DESCRIPCION -> imprime el puerto reservado
  local name="$1" cat="$2" desc="$3" p
  for p in $("$PY" "$CTL" suggest --name "$name" --project SegurPlus --category "$cat" --count 10 | grep -v '^#' | tr ' ' '\n' | grep -E '^[0-9]+$'); do
    [ -z "$(ss -ltnH "sport = :$p" 2>/dev/null)" ] || continue
    "$PY" "$CTL" reserve --name "$name" --project SegurPlus --description "$desc" --category "$cat" --port "$p" >/dev/null 2>&1 || continue
    RESERVED+=("$p"); echo "$p"; return 0
  done
  return 1
}

dc_up()  { docker compose -f "$REPO/deploy/docker-compose.yml" --env-file "$ENVF" --profile mockdify "$@"; }
dc_all() { docker compose -f "$REPO/deploy/docker-compose.yml" --env-file "$ENVF" --profile mockdify --profile seed "$@"; }

finish() {
  local rc=$?
  trap - EXIT
  if [ "$KEEP" = 1 ] && [ -n "$TMP" ]; then
    echo; echo "--keep: NO se desmonta. Proyecto $PROJECT, directorio $TMP, puertos ${RESERVED[*]:-ninguno} (siguen reservados)."
  else
    log "Desmontaje"
    if [ "$STARTED" = 1 ]; then
      dc_all down -v --remove-orphans --rmi local >"$TMP/down.log" 2>&1
      ok_or_fail $? "docker compose down -v (contenedores, red, volúmenes e imágenes locales del proyecto $PROJECT)"
      left="$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" -q | wc -l)"
      [ "$left" = 0 ]; ok_or_fail $? "no quedan contenedores del proyecto $PROJECT"
    fi
    if [ -n "$TMP" ]; then
      # los bind mounts de PostgreSQL los crea otro uid (70): se borran desde un contenedor
      [ -d "$TMP/data" ] && docker run --rm -v "$TMP:/t" --entrypoint rm postgres:16-alpine -rf /t/data >/dev/null 2>&1
      rm -rf "$TMP"; [ ! -e "$TMP" ]; ok_or_fail $? "directorio temporal borrado"
    fi
    for p in "${RESERVED[@]:-}"; do [ -n "$p" ] && "$PY" "$CTL" free "$p" >/dev/null 2>&1; done
    still=0; for p in "${RESERVED[@]:-}"; do [ -n "$p" ] && "$PY" "$CTL" list 2>/dev/null | grep -q "^$p/tcp" && still=1; done
    [ "$still" = 0 ]; ok_or_fail $? "puertos liberados del Port Panel (${RESERVED[*]:-ninguno})"
  fi

  live_state > "$LIVE_AFTER_FILE" 2>&1
  if diff -q "$LIVE_BEFORE_FILE" "$LIVE_AFTER_FILE" >/dev/null; then
    rep "OK   " "instancia viva intacta: segurplus-web-1/postgres-1/minio-1 (mismos id, arranque y puertos) y deploy/data sin cambios"
  else
    rep "FALLO" "la instancia viva CAMBIÓ (diferencias, sin secretos):"; diff "$LIVE_BEFORE_FILE" "$LIVE_AFTER_FILE" | sed 's/^/        /'
  fi

  echo; echo "==================== INFORME clone-test ($((SECONDS - T0)) s) ===================="
  printf '%s\n' "${REPORT[@]}"
  nf=$(printf '%s\n' "${REPORT[@]}" | grep -c '^FALLO')
  echo "---- resultado: $([ "$nf" = 0 ] && [ "$rc" = 0 ] && echo 'TODO OK' || echo "$nf punto(s) en FALLO")"
  rm -f "$LIVE_BEFORE_FILE" "$LIVE_AFTER_FILE"
  [ "$nf" = 0 ] && [ "$rc" = 0 ]; exit $?
}

LIVE_BEFORE_FILE="$(mktemp)"; LIVE_AFTER_FILE="$(mktemp)"
live_state > "$LIVE_BEFORE_FILE" 2>&1
trap finish EXIT

# ---------------------------------------------------------------- 1. clone
log "Clone limpio"
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
dirty="$(git -C "$ROOT" status --porcelain -- deploy scripts .github generator/Dockerfile backend/Dockerfile frontend/Dockerfile .env.example .dockerignore | wc -l)"
[ "$dirty" = 0 ] || echo "AVISO: hay $dirty cambio(s) sin commitear en ficheros de despliegue: la prueba usa lo COMMITEADO."
TMP="$(mktemp -d)"; REPO="$TMP/repo"; ENVF="$TMP/clone.env"
git clone -q --branch "$BRANCH" "$ROOT" "$REPO"; rc=$?
ok_or_fail $rc "git clone de la rama $BRANCH ($(git -C "$REPO" rev-parse --short HEAD)) en un directorio temporal"
[ $rc = 0 ] || exit 1
[ ! -e "$REPO/deploy/.env" ] && [ ! -d "$REPO/frontend/node_modules" ] && [ ! -d "$REPO/backend/target" ]; ok_or_fail $? "el clone no trae .env, node_modules ni target (no hay nada preinstalado)"

# ---------------------------------------------------------------- 2. puertos y .env
log "Puertos (Port Panel) y .env aleatorio"
P_BACK="$(reserve_port clone-backend backend 'clone-test: backend (temporal)')" &&
P_PG="$(reserve_port clone-postgres db 'clone-test: postgres (temporal)')" &&
P_MINIO="$(reserve_port clone-minio-api misc 'clone-test: MinIO API (temporal)')" &&
P_CONSOLE="$(reserve_port clone-minio-console misc 'clone-test: MinIO consola (temporal)')" &&
P_WEB="$(reserve_port clone-web web 'clone-test: web Caddy (temporal)')"
rc=$?
# las reservas hechas en subshell $(...) no actualizan RESERVED: se reconstruye
RESERVED=(${P_BACK:-} ${P_PG:-} ${P_MINIO:-} ${P_CONSOLE:-} ${P_WEB:-})
ok_or_fail $rc "puertos reservados en el Port Panel: backend $P_BACK, postgres $P_PG, minio $P_MINIO/$P_CONSOLE, web $P_WEB"
[ $rc = 0 ] || exit 1

rnd() { openssl rand -base64 "$1" | tr -d '/+=\n'; }
mkdir -p "$TMP/data/postgres" "$TMP/data/minio" "$TMP/data/caddy"
cat > "$ENVF" <<EOF
COMPOSE_PROJECT_NAME=$PROJECT
DATA_DIR=$TMP/data
LOCAL_UID=$(id -u)
LOCAL_GID=$(id -g)
POSTGRES_PORT=$P_PG
MINIO_API_PORT=$P_MINIO
MINIO_CONSOLE_PORT=$P_CONSOLE
BACKEND_PORT=$P_BACK
WEB_BIND_IP=127.0.0.1
WEB_PORT=$P_WEB
POSTGRES_USER=clone_user
POSTGRES_PASSWORD=$(rnd 24)
POSTGRES_DB=segurplus
MINIO_ROOT_USER=clone_minio
MINIO_ROOT_PASSWORD=$(rnd 24)
JWT_SECRET=$(rnd 48)
APP_CLOCK_FIXED_DATE=2027-01-15
SEGURPLUS_SEED_ENABLED=true
SEGURPLUS_SEED_DEFAULT_PASSWORD=$(rnd 18)
DIFY_BASE_URL=http://mockdify:8080
DIFY_KNOWLEDGE_API_KEY=mock-knowledge
DIFY_APP_API_KEY=mock-app
DIFY_PLANNER_API_KEY=mock-planner
AI_INDEXER_ENABLED=true
EOF
chmod 600 "$ENVF"
rep "OK   " ".env temporal con secretos aleatorios (proyecto $PROJECT, DATA_DIR propio, Dify simulado; web solo en 127.0.0.1)"
DB_USER="clone_user"; DB_NAME="segurplus"

# ---------------------------------------------------------------- 3. up --build
log "docker compose up -d --build (primera vez: descarga de Maven/Node, varios minutos)"
STARTED=1
dc_up up -d --build >"$TMP/up.log" 2>&1; rc=$?
ok_or_fail $rc "docker compose up -d --build (backend, web, postgres, minio, mockdify)"
[ $rc = 0 ] || { tail -n 30 "$TMP/up.log"; exit 1; }

log "Esperando salud del backend"
status=""
for _ in $(seq 1 90); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$(dc_up ps -q backend)" 2>/dev/null)"
  [ "$status" = healthy ] && break; sleep 4
done
[ "$status" = healthy ]; ok_or_fail $? "backend healthy (HEALTHCHECK de la imagen: $status)"
[ "$status" = healthy ] || { dc_up logs --tail 40 backend 2>&1 | sed -E 's/(password|secret|token)=[^ ]*/\1=***/Ig'; exit 1; }
for s in postgres mockdify; do
  st="$(docker inspect -f '{{.State.Status}}' "$(dc_up ps -q $s)" 2>/dev/null)"; [ "$st" = running ]; ok_or_fail $? "$s en ejecución"
done
[ -z "$(dc_up ps -a --format '{{.Service}}' | grep -x seed)" ]; ok_or_fail $? "el seed NO se ejecutó con 'up' (perfil seed)"

# ---------------------------------------------------------------- 4. seed
log "Seed (resetea datos del clone: perfil seed)"
dc_all run --rm seed >"$TMP/seed.log" 2>&1; rc=$?
ok_or_fail $rc "seed: dataset, 32 documentos, MinIO y tabla documents ($(grep -E '^[0-9]+ documentos|^MinIO' "$TMP/seed.log" | tr '\n' ' '))"
[ $rc = 0 ] || { tail -n 20 "$TMP/seed.log"; exit 1; }

log "Esperando a que el indexador indexe los 32 documentos en el Dify simulado"
idx=""
for _ in $(seq 1 90); do
  idx="$(dc_up exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "select count(*) filter (where index_status='INDEXED') || '/' || count(*) from documents" 2>/dev/null)"
  [ "$idx" = "32/32" ] && break; sleep 5
done
[ "$idx" = "32/32" ]; ok_or_fail $? "indexador: documentos INDEXED/total = $idx"

# ---------------------------------------------------------------- 5. checklist funcional
log "Checklist: logins, roles, chat por rol, acceso cruzado"
psql_q() { dc_up exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | head -n 1 | tr -d '[:space:]'; }
OWN="$(psql_q "select d.code from documents d join users u on u.id=d.owner_id where u.employee_code='EMP001' order by d.code limit 1")"
OTHER="$(psql_q "select d.code from documents d join users u on u.id=d.owner_id where u.employee_code='EMP002' order by d.code limit 1")"
echo "documentos de control: EMP001=$OWN EMP002=$OTHER"
"$PY" -I "$ROOT/scripts/clone_checks.py" --base "https://127.0.0.1:$P_WEB" --env-file "$ENVF" --own-code "$OWN" --other-code "$OTHER" | tee "$TMP/checks.log"
while IFS= read -r l; do case "$l" in "OK   "*|"FALLO"*) REPORT+=("$l");; esac; done < "$TMP/checks.log"

mock="$(dc_up logs mockdify 2>/dev/null)"
nr="$(printf '%s' "$mock" | grep -c 'retrieve')"; nc="$(printf '%s' "$mock" | grep -c 'chat-messages')"
[ "$nr" -gt 0 ] && [ "$nc" -gt 0 ]; ok_or_fail $? "el chat pasó por el Dify simulado (retrieve: $nr llamadas, chat-messages: $nc)"
exit 0
