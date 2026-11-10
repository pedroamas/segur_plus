#!/usr/bin/env bash
# Escaneo sencillo de secretos sobre los ficheros VERSIONADOS (git ls-files). Imprime solo "fichero:línea" y el tipo de hallazgo,
# nunca el valor. Sale con 1 si encuentra algo.
#   - patrones: claves de Google (AIza...), sk-..., app-... de Dify, cabeceras de clave privada, AWS, tokens de GitHub
#   - valores literales de deploy/.env (si existe) de variables que parecen secretos (PASSWORD, SECRET, KEY, TOKEN)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

found=0
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git ls-files -z --cached --exclude-standard > "$tmp/files"
# los binarios no se escanean (-I); el propio script y su doc contienen los patrones como texto
xargs -0 -a "$tmp/files" grep -nIE -e 'AIza[0-9A-Za-z_-]{35}' -e 'sk-[A-Za-z0-9_-]{20,}' -e 'app-[A-Za-z0-9]{20,}' \
  -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' -e 'AKIA[0-9A-Z]{16}' -e 'gh[pousr]_[A-Za-z0-9]{30,}' -- 2>/dev/null \
  | grep -vE '^scripts/scan-secrets\.sh:' | cut -d: -f1,2 | sed 's/$/  patrón de secreto/' > "$tmp/hits"
if [ -s "$tmp/hits" ]; then cat "$tmp/hits"; found=1; fi

if [ -r deploy/.env ]; then
  # valores de 12+ caracteres de variables sensibles, como cadenas fijas (sin imprimirlos)
  grep -E '^[A-Z0-9_]*(PASSWORD|SECRET|KEY|TOKEN)[A-Z0-9_]*=' deploy/.env | cut -d= -f2- \
    | sed -e 's/^["'\'']//' -e 's/["'\'']$//' | awk 'length($0) >= 12' > "$tmp/values"
  if [ -s "$tmp/values" ]; then
    xargs -0 -a "$tmp/files" grep -nIF -f "$tmp/values" -- 2>/dev/null | cut -d: -f1,2 | sed 's/$/  valor de deploy\/.env/' > "$tmp/hits2"
    if [ -s "$tmp/hits2" ]; then cat "$tmp/hits2"; found=1; fi
  fi
  echo "(valores de deploy/.env comprobados: $(wc -l < "$tmp/values") sin mostrar)"
else
  echo "(deploy/.env no existe: solo patrones)"
fi

if [ "$found" = 0 ]; then echo "Sin secretos en los $(tr -cd '\0' < "$tmp/files" | wc -c) ficheros versionados."; fi
exit "$found"
