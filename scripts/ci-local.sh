#!/usr/bin/env bash
# CI local de SegurPlus: los MISMOS pasos que .github/workflows/ci.yml (que llama a este script paso a paso).
#   scripts/ci-local.sh              # todos los pasos, en orden; se detiene en el primer fallo
#   scripts/ci-local.sh frontend     # solo los pasos indicados (backend generator frontend compose secrets)
# Pasos:
#   backend    ./mvnw verify  (JUnit + ArchUnit + Testcontainers; requiere Docker)
#   generator  dependencias, importación y recolección de los tests de pytest. Los tests de pytest NO se ejecutan: todos necesitan la BD
#              sembrada (y los live, el backend y MinIO) y se corren a mano contra el stack vivo (generator/README.md).
#   frontend   npm ci, comprobación de tipos (tsc), build de Vite y Vitest, en una COPIA temporal (no toca frontend/node_modules ni
#              frontend/dist, que sirve el Caddy vivo). NO ejecuta Playwright: los E2E reales gastan modelo.
#   compose    docker compose config -q con un .env derivado de .env.example (perfiles por defecto, seed, mockdify y superposición host-backend)
#   secrets    scripts/scan-secrets.sh sobre git ls-files
# Variables: CI_TMP (directorio de trabajo; por defecto uno temporal que se borra).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2
ALL=(backend generator frontend compose secrets)
STEPS=("$@"); [ ${#STEPS[@]} -gt 0 ] || STEPS=("${ALL[@]}")
for s in "${STEPS[@]}"; do
  case " ${ALL[*]} " in *" $s "*) ;; *) echo "Paso desconocido: $s (válidos: ${ALL[*]})" >&2; exit 2 ;; esac
done

TMP="${CI_TMP:-$(mktemp -d)}"; mkdir -p "$TMP"
[ -n "${CI_TMP:-}" ] || trap 'rm -rf "$TMP"' EXIT

# Ejecuta un comando con acceso a Docker aunque la sesión no tenga el grupo `docker` (sg docker -c)
with_docker() {
  if docker info >/dev/null 2>&1; then "$@"; else sg docker -c "$(printf '%q ' "$@")"; fi
}

step_backend() {
  cd "$ROOT/backend" || return 1
  local n=0
  while :; do
    with_docker ./mvnw -B -ntp verify && return 0
    n=$((n + 1)); [ "$n" -lt 2 ] || return 1
    echo ">> verify falló; un reintento (por si fue por puertos/contenedores de Testcontainers)"; sleep 5
  done
}

step_generator() {
  cd "$ROOT/generator" || return 1
  python3 -m venv "$TMP/genvenv" && "$TMP/genvenv/bin/pip" install -q -r requirements.txt || return 1
  "$TMP/genvenv/bin/python" -c "import segurplus_gen.seed, segurplus_gen.build" || return 1
  "$TMP/genvenv/bin/python" -m pytest --collect-only -q -p no:cacheprovider | tail -n 3
  [ "${PIPESTATUS[0]}" = 0 ]
}

step_frontend() {
  rm -rf "$TMP/frontend" && mkdir -p "$TMP/frontend" || return 1
  tar -C "$ROOT/frontend" --exclude=node_modules --exclude=dist --exclude=test-results -cf - . | tar -C "$TMP/frontend" -xf - || return 1
  cd "$TMP/frontend" || return 1
  npm ci --no-audit --no-fund && npx tsc --noEmit && npm run build && npm test
}

step_compose() {
  # .env de ejemplo derivado de .env.example (los valores de ejemplo ya son válidos)
  cp "$ROOT/.env.example" "$TMP/ci.env" || return 1
  local base=(docker compose -f "$ROOT/deploy/docker-compose.yml")
  "${base[@]}" --env-file "$TMP/ci.env" config -q || return 1
  "${base[@]}" --env-file "$TMP/ci.env" --profile seed --profile mockdify config -q || return 1
  "${base[@]}" -f "$ROOT/deploy/docker-compose.host-backend.yml" --env-file "$TMP/ci.env" config -q || return 1
  # los servicios y perfiles esperados existen
  local svc; svc="$("${base[@]}" --env-file "$TMP/ci.env" --profile seed --profile mockdify config --services | sort | tr '\n' ' ')"
  [ "$svc" = "backend minio mockdify postgres seed web " ] || { echo "servicios inesperados: $svc"; return 1; }
  # sin perfiles, `up` NO debe incluir seed ni mockdify
  svc="$("${base[@]}" --env-file "$TMP/ci.env" config --services | sort | tr '\n' ' ')"
  [ "$svc" = "backend minio postgres web " ] || { echo "servicios por defecto inesperados: $svc"; return 1; }
}

step_secrets() { "$ROOT/scripts/scan-secrets.sh"; }

declare -A RESULT TIME
fail=0
summary() {
  echo; echo "==================== RESUMEN ci-local ===================="
  for s in "${STEPS[@]}"; do printf '  %-10s %-8s %5ss\n' "$s" "${RESULT[$s]:-OMITIDO}" "${TIME[$s]:--}"; done
  echo "=========================================================="
}
trap 'summary; [ -n "${CI_TMP:-}" ] || rm -rf "$TMP"' EXIT

for s in "${STEPS[@]}"; do
  echo; echo "==================== paso: $s ===================="
  t0=$SECONDS
  ( set -o pipefail; "step_$s" )
  rc=$?
  TIME[$s]=$((SECONDS - t0))
  if [ $rc -eq 0 ]; then RESULT[$s]=OK; else RESULT[$s]=FALLO; fail=1; echo "!! el paso $s falló (código $rc)"; break; fi
done
exit $fail
