#!/usr/bin/env python3
"""Checklist funcional de un despliegue de SegurPlus (lo usa scripts/clone-test.sh). Solo stdlib; NUNCA imprime contraseñas ni tokens.

Uso: clone_checks.py --base https://127.0.0.1:PUERTO --env-file RUTA_ENV --own-code D14 --other-code D17
  --base        URL del frontend (Caddy): así se prueba también el proxy /api del Caddyfile
  --env-file    .env del despliegue; de ahí se lee SEGURPLUS_SEED_DEFAULT_PASSWORD (no se imprime)
  --own-code    código de un documento de EMP001 (control positivo: debe verlo)
  --other-code  código de un documento de EMP002 (EMP001 debe recibir 404)
Salida: una línea `OK|FALLO  descripción` por punto. Código de salida 1 si algún punto falla.
"""
import argparse
import json
import ssl
import sys
import urllib.error
import urllib.request

USERS = {"DIR001": "MANAGER", "ACC001": "ACCOUNTANT", "COM001": "SALES_MANAGER",
         "EMP001": "EMPLOYEE", "EMP002": "EMPLOYEE", "EMP003": "EMPLOYEE", "EMP004": "EMPLOYEE"}
CHAT_USERS = ["DIR001", "ACC001", "COM001", "EMP001"]   # una pregunta por rol
QUESTION = "¿Qué dice la documentación sobre las comisiones de los empleados?"

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE    # CA interna de Caddy (tls internal)
failures = 0


def report(ok: bool, text: str) -> None:
    global failures
    failures += 0 if ok else 1
    print(f"{'OK   ' if ok else 'FALLO'}  {text}", flush=True)


def read_password(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("SEGURPLUS_SEED_DEFAULT_PASSWORD="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("falta SEGURPLUS_SEED_DEFAULT_PASSWORD en el .env")


def request(base, path, method="GET", token=None, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        return urllib.request.urlopen(req, context=CTX, timeout=timeout)
    except urllib.error.HTTPError as e:
        return e


def get_json(resp):
    try:
        return json.loads(resp.read() or b"{}")
    except ValueError:
        return {}


def login(base, user, password):
    r = request(base, "/api/v1/auth/login", "POST", body={"username": user, "password": password})
    if r.status != 200:
        return None
    return get_json(r).get("accessToken")


def chat_until_done(base, token):
    """Devuelve (terminó con `done`, hubo `error`, eventos vistos)."""
    r = request(base, "/api/v1/chat", "POST", token, {"message": QUESTION}, timeout=90)
    if r.status != 200:
        return False, False, [f"HTTP {r.status}"]
    events, current = [], None
    for raw in r:
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if line.startswith("event:"):
            current = line[6:].strip()
            events.append(current)
            if current in ("done", "error"):
                break
    return "done" in events, "error" in events, events


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--env-file", required=True)
    ap.add_argument("--own-code", required=True)
    ap.add_argument("--other-code", required=True)
    a = ap.parse_args()
    base, password = a.base.rstrip("/"), read_password(a.env_file)

    r = request(base, "/")
    report(r.status == 200, f"web: GET / sirve el frontend por HTTPS (HTTP {r.status})")
    r = request(base, "/api/v1/users/me")
    report(r.status == 401, f"web: el proxy /api llega al backend (users/me sin token => HTTP {r.status}, esperado 401)")

    tokens = {}
    for user in USERS:
        tokens[user] = login(base, user, password)
        report(tokens[user] is not None, f"login {user}")

    for user, role in USERS.items():
        if not tokens[user]:
            report(False, f"users/me {user}: sin token")
            continue
        r = request(base, "/api/v1/users/me", token=tokens[user])
        body = get_json(r)
        report(r.status == 200 and body.get("role") == role and body.get("employeeCode") == user,
               f"users/me {user} => rol {body.get('role')} (esperado {role})")
        report("monthlySalary" not in body and "monthly_salary" not in body, f"users/me {user}: sin salario en el DTO")

    for user in CHAT_USERS:
        if not tokens[user]:
            report(False, f"chat {user}: sin token")
            continue
        done, err, events = chat_until_done(base, tokens[user])
        report(done and not err, f"chat {user} ({USERS[user]}): SSE termina con evento done"
                                 + ("" if done and not err else f" (eventos: {','.join(events)})"))

    # Acceso cruzado y control positivo (REST)
    if tokens["DIR001"] and tokens["EMP001"]:
        page = get_json(request(base, "/api/v1/documents?size=100", token=tokens["DIR001"]))
        ids = {d.get("code"): d.get("id") for d in page.get("items", [])}
        report(len(ids) == 32, f"DIR001 (MANAGER) lista los 32 documentos ({len(ids)})")
        emp = get_json(request(base, "/api/v1/documents?size=100", token=tokens["EMP001"]))
        emp_codes = {d.get("code") for d in emp.get("items", [])}
        report(a.other_code not in emp_codes and a.own_code in emp_codes,
               f"EMP001: su listado incluye su documento {a.own_code} y excluye el de EMP002 ({a.other_code})")
        own, other = ids.get(a.own_code), ids.get(a.other_code)
        if own and other:
            r = request(base, f"/api/v1/documents/{own}", token=tokens["EMP001"])
            report(r.status == 200, f"EMP001 lee su propio documento {a.own_code} (HTTP {r.status})")
            r = request(base, f"/api/v1/documents/{other}", token=tokens["EMP001"])
            report(r.status == 404, f"EMP001 pide el documento de EMP002 ({a.other_code}) => HTTP {r.status} (esperado 404)")
            r = request(base, f"/api/v1/documents/{other}/download", token=tokens["EMP001"])
            report(r.status == 404, f"EMP001 descarga el documento de EMP002 => HTTP {r.status} (esperado 404)")
        else:
            report(False, "no se encontraron los documentos de control en el listado de DIR001")
    else:
        report(False, "cruce: faltan tokens de DIR001/EMP001")

    print(f"\n{'TODO OK' if failures == 0 else str(failures) + ' punto(s) en FALLO'}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
