#!/usr/bin/env python3
"""Provisiona Dify para SegurPlus (F4). Idempotente: se puede ejecutar cuantas veces haga falta.

Qué deja creado (todo por la API de consola/servicio de Dify 1.18; no hay que tocar la UI):
  1. Proveedor de modelos Gemini (plugin langgenius/gemini ya instalado) con la clave de GEMINI_API_KEY.
  2. Modelos por defecto del workspace: embedding fijado (EMBEDDING_MODEL) y LLM (GEMINI_MODEL).
  3. 4 bases de conocimiento «SegurPlus General/Direccion/Finanzas/Empleados» con ese embedding.
  4. 4 apps de chat sin conocimiento: planificador y generador, cada uno con modelo principal y de reserva.
  5. Claves de API (Knowledge y de cada app) escritas en deploy/.env (DIFY_BASE_URL, DIFY_KNOWLEDGE_API_KEY, DIFY_APP_API_KEY,
     DIFY_APP_API_KEY_FALLBACK, DIFY_PLANNER_API_KEY, DIFY_PLANNER_API_KEY_FALLBACK).

Credenciales (NUNCA por chat ni en el repositorio):
  ~/.config/segurplus/dify_admin.env  (chmod 600)  con  DIFY_ADMIN_EMAIL=...  y  DIFY_ADMIN_PASSWORD=...
  La clave de Gemini se lee de deploy/.env (GEMINI_API_KEY). El script no imprime ningún secreto.

Variables opcionales (entorno o deploy/.env): DIFY_BASE_URL (def. http://192.168.0.28:8088), GEMINI_MODEL (def. gemini-3.5-flash),
GEMINI_MODEL_FALLBACK (def. gemini-3-flash-preview), EMBEDDING_MODEL (def. gemini-embedding-001).

AVISO: el embedding queda fijado en los datasets al crearlos. Cambiarlo obliga a reindexar (ver docs/adr/0008-embedding.md).
"""
import base64
import http.cookiejar
import json
import os
import stat
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ENV_FILE = REPO / "deploy" / ".env"
ADMIN_FILE = Path(os.environ.get("DIFY_ADMIN_FILE", Path.home() / ".config" / "segurplus" / "dify_admin.env"))
PROVIDER = "langgenius/gemini/google"
KB_NAMES = ["SegurPlus General", "SegurPlus Direccion", "SegurPlus Finanzas", "SegurPlus Empleados"]
APPS = {  # nombre de app -> (variable de .env, prompt, modelo: principal|reserva, max_output_tokens)
    "SegurPlus Planificador": ("DIFY_PLANNER_API_KEY", "planner_prompt.txt", "primary", 600),
    "SegurPlus Planificador (reserva)": ("DIFY_PLANNER_API_KEY_FALLBACK", "planner_prompt.txt", "fallback", 600),
    "SegurPlus Asistente": ("DIFY_APP_API_KEY", "generator_prompt.txt", "primary", 2048),
    "SegurPlus Asistente (reserva)": ("DIFY_APP_API_KEY_FALLBACK", "generator_prompt.txt", "fallback", 2048),
}


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"').strip("'")
    return values


def write_env_var(name, value):
    """Fija name=value en deploy/.env (crea o reemplaza la línea; conserva el resto y los permisos 600)."""
    lines = ENV_FILE.read_text().splitlines() if ENV_FILE.exists() else []
    out, done = [], False
    for line in lines:
        if line.split("=", 1)[0].strip() == name:
            out.append(f"{name}={value}")
            done = True
        else:
            out.append(line)
    if not done:
        out.append(f"{name}={value}")
    tmp = ENV_FILE.with_suffix(".tmp")
    tmp.write_text("\n".join(out) + "\n")
    os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    tmp.replace(ENV_FILE)


class Dify:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None

    def _cookie(self, name):
        for c in self.jar:
            if c.name.endswith(name):
                return c.value
        return None

    def call(self, method, path, body=None, bearer=None, timeout=120, ok=(200, 201, 204)):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if bearer:
            req.add_header("Authorization", f"Bearer {bearer}")
        elif self.csrf:
            req.add_header("X-CSRF-Token", self._cookie("csrf_token") or self.csrf)
        try:
            with self.opener.open(req, timeout=timeout) as res:
                raw = res.read()
                return res.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                payload = json.loads(raw)
            except Exception:
                payload = None
            if e.code in ok:
                return e.code, payload
            # el cuerpo de error de Dify no lleva secretos nuestros, pero se recorta por prudencia
            msg = (payload or {}).get("message") or (payload or {}).get("code") or ""
            raise SystemExit(f"ERROR {method} {path} -> HTTP {e.code} {str(msg)[:200]}")

    def login(self, email, password):
        status, body = self.call("POST", "/console/api/login", {
            "email": email, "password": base64.b64encode(password.encode()).decode(), "remember_me": True, "language": "es-ES"})
        if not body or body.get("result") != "success":
            raise SystemExit("ERROR: login en Dify rechazado (revisa DIFY_ADMIN_EMAIL / DIFY_ADMIN_PASSWORD).")
        self.csrf = self._cookie("csrf_token")
        if not self.csrf:
            raise SystemExit("ERROR: Dify no devolvió token CSRF.")

    def console(self, method, path, body=None, **kw):
        return self.call(method, "/console/api" + path, body, **kw)


def log(msg):
    print(msg, flush=True)


def main():
    env = {**read_env(ENV_FILE), **{k: v for k, v in os.environ.items() if k.startswith(("DIFY_", "GEMINI_", "EMBEDDING_"))}}
    admin = read_env(ADMIN_FILE)
    email, password = admin.get("DIFY_ADMIN_EMAIL"), admin.get("DIFY_ADMIN_PASSWORD")
    if not email or not password:
        raise SystemExit(f"Faltan DIFY_ADMIN_EMAIL / DIFY_ADMIN_PASSWORD en {ADMIN_FILE} (chmod 600).")
    mode = ADMIN_FILE.stat().st_mode
    if mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise SystemExit(f"{ADMIN_FILE} debe tener permisos 600 (chmod 600 {ADMIN_FILE}).")
    gemini_key = env.get("GEMINI_API_KEY")
    if not gemini_key:
        raise SystemExit("Falta GEMINI_API_KEY en deploy/.env.")
    base = env.get("DIFY_BASE_URL") or "http://192.168.0.28:8088"
    model_primary = env.get("GEMINI_MODEL") or "gemini-3.5-flash"
    model_fallback = env.get("GEMINI_MODEL_FALLBACK") or "gemini-3-flash-preview"
    embedding = env.get("EMBEDDING_MODEL") or "gemini-embedding-001"

    d = Dify(base)
    d.login(email, password)
    log("login OK")

    # 1) plugin y proveedor ---------------------------------------------------------------------------------------------
    _, plugins = d.console("GET", "/workspaces/current/plugin/list?page=1&page_size=100")
    ids = [p.get("plugin_id") for p in (plugins or {}).get("plugins", [])]
    if "langgenius/gemini" not in ids:
        raise SystemExit("El plugin langgenius/gemini no está instalado en Dify: instálalo desde Plugins > Marketplace y reejecuta.")
    _, providers = d.console("GET", "/workspaces/current/model-providers")
    prov = next((p for p in (providers or {}).get("data", []) if p.get("provider") == PROVIDER), None)
    configured = bool(prov) and (prov.get("custom_configuration") or {}).get("status") == "active"
    if not configured:
        d.console("POST", f"/workspaces/current/model-providers/{PROVIDER}/credentials",
                  {"credentials": {"google_api_key": gemini_key}, "name": "SegurPlus"})
        log("proveedor Gemini configurado")
    else:
        log("proveedor Gemini ya configurado")

    # 2) modelos por defecto --------------------------------------------------------------------------------------------
    d.console("POST", "/workspaces/current/default-model", {"model_settings": [
        {"model_type": "text-embedding", "provider": PROVIDER, "model": embedding},
        {"model_type": "llm", "provider": PROVIDER, "model": model_primary}]})
    log(f"modelos por defecto: embedding={embedding}, llm={model_primary} (reserva={model_fallback})")

    # 3) clave Knowledge ------------------------------------------------------------------------------------------------
    knowledge_key = env.get("DIFY_KNOWLEDGE_API_KEY") or ""
    if not knowledge_key or d.call("GET", "/v1/datasets?page=1&limit=1", bearer=knowledge_key, ok=(200, 401, 403))[0] != 200:
        _, created = d.console("POST", "/datasets/api-keys", {})
        knowledge_key = created["token"]
        write_env_var("DIFY_KNOWLEDGE_API_KEY", knowledge_key)
        log("clave Knowledge creada y guardada en deploy/.env")
    else:
        log("clave Knowledge vigente")
    write_env_var("DIFY_BASE_URL", base)

    # 4) datasets -------------------------------------------------------------------------------------------------------
    _, existing = d.call("GET", "/v1/datasets?page=1&limit=100", bearer=knowledge_key)
    have = {x["name"]: x for x in existing.get("data", [])}
    for name in KB_NAMES:
        if name in have:
            ds = have[name]
            log(f"dataset «{name}» ya existe")
        else:
            _, ds = d.call("POST", "/v1/datasets", {
                "name": name, "description": f"Base de conocimiento de SegurPlus ({name.split()[-1]})",
                "indexing_technique": "high_quality", "permission": "only_me",
                "embedding_model": embedding, "embedding_model_provider": PROVIDER,
                "retrieval_model": {"search_method": "semantic_search", "reranking_enable": False, "top_k": 5,
                                    "score_threshold_enabled": False}}, bearer=knowledge_key)
            log(f"dataset «{name}» creado")
        if ds.get("embedding_model") and ds.get("embedding_model") != embedding:
            log(f"  AVISO: «{name}» usa el embedding {ds.get('embedding_model')} (esperado {embedding}); ver docs/adr/0008-embedding.md para reindexar")

    # 5) apps -----------------------------------------------------------------------------------------------------------
    for app_name, (var, prompt_file, which, max_tokens) in APPS.items():
        model = model_primary if which == "primary" else model_fallback
        prompt = (HERE / prompt_file).read_text().strip()
        _, listing = d.console("GET", "/apps?page=1&limit=100&name=" + urllib.request.quote(app_name))
        app = next((a for a in (listing or {}).get("data", []) if a.get("name") == app_name), None)
        if not app:
            _, app = d.console("POST", "/apps", {"name": app_name, "mode": "chat", "icon_type": "emoji", "icon": "🛡️",
                                                  "icon_background": "#FFEAD5",
                                                  "description": "Gestionada por deploy/dify/provision_dify.py"})
            log(f"app «{app_name}» creada")
        app_id = app["id"]
        _, detail = d.console("GET", f"/apps/{app_id}")
        cfg = dict(detail.get("model_config") or {})
        cfg.update({
            "pre_prompt": prompt, "prompt_type": "simple", "opening_statement": "", "suggested_questions": [],
            "more_like_this": {"enabled": False}, "speech_to_text": {"enabled": False}, "text_to_speech": {"enabled": False},
            "retriever_resource": {"enabled": False}, "sensitive_word_avoidance": {"enabled": False, "type": "", "configs": []},
            "suggested_questions_after_answer": {"enabled": False}, "user_input_form": [], "file_upload": {"image": {"enabled": False}},
            "dataset_configs": {"retrieval_model": "single", "datasets": {"datasets": []}},   # SIN conocimiento (ADR 0003)
            "agent_mode": {"enabled": False, "max_iteration": 5, "strategy": "function_call", "tools": []},
            "model": {"provider": PROVIDER, "name": model, "mode": "chat", "completion_params": {
                "temperature": 0.2, "max_output_tokens": max_tokens, "thinking_level": "Low", "include_thoughts": False}}})
        d.console("POST", f"/apps/{app_id}/model-config", cfg)
        log(f"  «{app_name}»: modelo {model}, sin conocimiento")
        key = env.get(var) or ""
        if not key or d.call("GET", "/v1/parameters", bearer=key, ok=(200, 401, 403, 404))[0] != 200:
            _, created = d.console("POST", f"/apps/{app_id}/api-keys", {})
            write_env_var(var, created["token"])
            log(f"  clave de «{app_name}» creada y guardada en deploy/.env ({var})")

    log("OK: Dify listo. Reinicia el backend para que lea deploy/.env.")


if __name__ == "__main__":
    sys.exit(main())
