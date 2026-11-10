#!/usr/bin/env bash
# Comprueba que la instancia de Dify (dependencia EXTERNA de SegurPlus, no va en el compose) responde. Solo lecturas; nunca modifica nada.
# Uso: scripts/check-dify.sh [--strict] [--env-file RUTA]
#   Sin --strict avisa y sale con 0 (no rompe arranques ni scripts); con --strict sale con 1 si Dify no está operativo.
#   DIFY_BASE_URL y DIFY_KNOWLEDGE_API_KEY salen del entorno o, si no están, de --env-file (por defecto deploy/.env). Nunca se imprimen las claves.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STRICT=0
ENV_FILE="$ROOT/deploy/.env"
while [ $# -gt 0 ]; do
  case "$1" in
    --strict) STRICT=1 ;;
    --env-file) ENV_FILE="${2:?falta la ruta}"; shift ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "Argumento desconocido: $1" >&2; exit 2 ;;
  esac
  shift
done

# Lee UNA variable de un .env sin hacer source (y sin imprimir nada más)
env_var() { [ -r "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }

BASE="${DIFY_BASE_URL:-$(env_var DIFY_BASE_URL)}"
BASE="${BASE:-http://192.168.0.28:8088}"
BASE="${BASE%/}"
KEY="${DIFY_KNOWLEDGE_API_KEY:-$(env_var DIFY_KNOWLEDGE_API_KEY)}"

fail() { echo "AVISO: $1"; echo "       El backend arranca igualmente, pero el chat responderá 503 y el indexador no podrá indexar (ver deploy/README.md)."; [ "$STRICT" = 1 ] && exit 1; exit 0; }

case "$BASE" in
  http://mockdify:*|https://mockdify:*)
    echo "INFO: DIFY_BASE_URL apunta al Dify SIMULADO (perfil mockdify, solo pruebas); solo es accesible desde la red del compose."
    exit 0 ;;
esac

echo "Dify: $BASE"
code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$BASE/v1/datasets?page=1&limit=1" 2>/dev/null)"
[ -z "$code" ] || [ "$code" = "000" ] && fail "Dify no responde en $BASE (¿está levantada la instancia, en ~/dify/docker?)."
echo "  responde (HTTP $code sin clave; 401 es lo normal)"

if [ -z "$KEY" ]; then
  fail "falta DIFY_KNOWLEDGE_API_KEY: ejecute  python3 deploy/dify/provision_dify.py  (escribe las claves en deploy/.env)."
fi
body="$(curl -s -m 10 -w '\n%{http_code}' -H @<(printf 'Authorization: Bearer %s\n' "$KEY") "$BASE/v1/datasets?page=1&limit=100" 2>/dev/null)"
code="$(printf '%s' "$body" | tail -n 1)"
[ "$code" = "200" ] || fail "la clave de Knowledge no es válida o Dify devolvió HTTP $code."
missing=""
for n in General Direccion Finanzas Empleados; do
  printf '%s' "$body" | grep -q "\"SegurPlus $n\"" || missing="$missing $n"
done
[ -z "$missing" ] || fail "faltan datasets de Dify:$missing (ejecute deploy/dify/provision_dify.py)."
echo "  clave de Knowledge válida y los 4 datasets 'SegurPlus ...' existen"
echo "OK: Dify operativo."
