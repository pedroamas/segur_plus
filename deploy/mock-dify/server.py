#!/usr/bin/env python3
"""Dify SIMULADO para pruebas y demos de SegurPlus. NO es Dify: no hay modelo, ni embeddings, ni autenticación real.

Implementa solo lo que usa el backend (DifyAiGateway, API 1.18): Knowledge API (datasets, metadatos, create-by-text, estado,
borrado, retrieve) y /v1/chat-messages (planificador en blocking, generador en streaming SSE). Acepta CUALQUIER clave de API.
El retrieve devuelve segmentos de los documentos de ESE dataset ordenados por solapamiento de palabras e IGNORA los filtros de
metadatos (el peor caso: el backend debe re-autorizar cada fragmento). Solo stdlib de Python; el estado se guarda en MOCK_DIFY_STATE.
"""
import json
import os
import re
import threading
import time
import unicodedata
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATASETS = {"mock-ds-general": "SegurPlus General", "mock-ds-direccion": "SegurPlus Direccion",
            "mock-ds-finanzas": "SegurPlus Finanzas", "mock-ds-empleados": "SegurPlus Empleados"}
STATE_FILE = os.environ.get("MOCK_DIFY_STATE", "/state/state.json")
LOCK = threading.Lock()
# documents[dataset_id][doc_id] = {"name", "text", "metadata": {nombre: valor}}; fields[dataset_id] = [{"id","name","type"}]
STATE = {"documents": {d: {} for d in DATASETS}, "fields": {d: [] for d in DATASETS}}


def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            saved = json.load(f)
        for d in DATASETS:
            STATE["documents"][d] = saved.get("documents", {}).get(d, {})
            STATE["fields"][d] = saved.get("fields", {}).get(d, [])
    except (OSError, ValueError):
        pass


def save_state():
    try:
        os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
        with open(STATE_FILE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(STATE, f)
        os.replace(STATE_FILE + ".tmp", STATE_FILE)
    except OSError:
        pass  # el estado persistente es un extra; sin él el mock sigue funcionando en memoria


def words(text):
    text = unicodedata.normalize("NFKD", text.lower())
    text = "".join(c for c in text if not unicodedata.combining(c))
    return {w for w in re.findall(r"[a-z0-9]{3,}", text)}


def segments(text):
    parts = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    return parts or ([text.strip()] if text.strip() else [])


def plan_for(prompt):
    marker = "PREGUNTA DEL USUARIO:"
    question = prompt.split(marker, 1)[1].strip() if marker in prompt else prompt.strip()
    return json.dumps({"retrieve": {"query": question[:250]}, "tools": []}, ensure_ascii=False)


def answer_for(prompt):
    codes = []
    for code in re.findall(r"<<<FRAGMENTO \[([A-Za-z0-9]+)\]", prompt):
        if code not in codes:
            codes.append(code)
    if not codes:
        return "Respuesta simulada (Dify mock): no hay fragmentos autorizados en el contexto para responder."
    return ("Respuesta simulada (Dify mock, sin modelo real). Según los documentos del contexto " + ", ".join(f"[{c}]" for c in codes[:2])
            + " hay información relacionada con tu pregunta.")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MockDify/1.0"

    def log_message(self, fmt, *args):  # sin cuerpos ni claves en el log: solo método, ruta y código
        print(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}", flush=True)

    # ------------------------------------------------------------------ utilidades
    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return {}

    def send_json(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def empty(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def route(self):
        return self.path.split("?")[0].rstrip("/")

    # ------------------------------------------------------------------ GET / DELETE
    def do_GET(self):
        p = self.route()
        if p in ("", "/health", "/v1/health"):
            return self.send_json({"status": "ok", "mock": True})
        if p == "/v1/datasets":
            return self.send_json({"data": [{"id": i, "name": n} for i, n in DATASETS.items()], "has_more": False, "total": len(DATASETS)})
        m = re.fullmatch(r"/v1/datasets/([^/]+)/metadata", p)
        if m and m.group(1) in DATASETS:
            return self.send_json({"doc_metadata": STATE["fields"][m.group(1)], "built_in_field_enabled": False})
        m = re.fullmatch(r"/v1/datasets/([^/]+)/documents/([^/]+)", p)
        if m and m.group(1) in DATASETS:
            doc = STATE["documents"][m.group(1)].get(m.group(2))
            if doc is None:
                return self.send_json({"code": "not_found"}, 404)
            return self.send_json({"id": m.group(2), "name": doc["name"], "indexing_status": "completed"})
        self.send_json({"code": "not_found"}, 404)

    def do_DELETE(self):
        m = re.fullmatch(r"/v1/datasets/([^/]+)/documents/([^/]+)", self.route())
        if m and m.group(1) in DATASETS:
            with LOCK:
                found = STATE["documents"][m.group(1)].pop(m.group(2), None)
                save_state()
            return self.empty(204 if found else 404)
        self.send_json({"code": "not_found"}, 404)

    # ------------------------------------------------------------------ POST
    def do_POST(self):
        p, body = self.route(), self.body()
        if p == "/v1/chat-messages":
            return self.chat(body)
        m = re.fullmatch(r"/v1/datasets/([^/]+)/(metadata|documents/metadata|document/create-by-text|retrieve)", p)
        if not m or m.group(1) not in DATASETS:
            return self.send_json({"code": "not_found"}, 404)
        ds, action = m.groups()
        with LOCK:
            if action == "metadata":
                field = {"id": str(uuid.uuid4()), "type": body.get("type", "string"), "name": body.get("name", "x")}
                STATE["fields"][ds].append(field)
                save_state()
                return self.send_json(field)
            if action == "documents/metadata":
                for op in body.get("operation_data", []):
                    doc = STATE["documents"][ds].get(op.get("document_id"))
                    if doc is not None:
                        doc["metadata"] = {x.get("name"): x.get("value") for x in op.get("metadata_list", [])}
                save_state()
                return self.send_json({"result": "success"})
            if action == "document/create-by-text":
                doc_id = str(uuid.uuid4())
                STATE["documents"][ds][doc_id] = {"name": body.get("name", "documento"), "text": body.get("text", ""), "metadata": {}}
                save_state()
                return self.send_json({"document": {"id": doc_id, "name": body.get("name", "documento")}, "batch": "mock-batch"})
        return self.retrieve(ds, body)  # action == "retrieve"

    def retrieve(self, ds, body):
        q = words(str(body.get("query", "")))
        top_k = int(body.get("retrieval_model", {}).get("top_k", 5) or 5)
        scored = []
        with LOCK:
            docs = {k: dict(v) for k, v in STATE["documents"][ds].items()}
        for doc_id, doc in docs.items():
            for n, seg in enumerate(segments(doc["text"])):
                w = words(seg)
                score = round(len(q & w) / (len(q) or 1), 4) if q else 0.0
                scored.append((score, doc_id, n, seg, doc))
        scored.sort(key=lambda t: (-t[0], t[1], t[2]))
        records = []
        for score, doc_id, n, seg, doc in scored[:top_k]:
            records.append({"score": score, "segment": {
                "id": f"{doc_id}-{n}", "content": seg,
                "document": {"id": doc_id, "name": doc["name"], "doc_metadata": dict(doc.get("metadata") or {})}}})
        self.send_json({"query": {"content": str(body.get("query", ""))}, "records": records})

    def chat(self, body):
        prompt = str(body.get("query", ""))
        if body.get("response_mode") != "streaming":  # planificador
            return self.send_json({"event": "message", "answer": plan_for(prompt),
                                   "metadata": {"usage": {"prompt_tokens": 0, "completion_tokens": 0}}})
        text = answer_for(prompt)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def event(obj):
            self.wfile.write(b"data: " + json.dumps(obj, ensure_ascii=False).encode() + b"\n\n")
            self.wfile.flush()

        event({"event": "ping"})
        step = max(1, len(text) // 4)
        for i in range(0, len(text), step):
            event({"event": "message", "answer": text[i:i + step]})
            time.sleep(0.05)
        event({"event": "message_end", "metadata": {"usage": {"prompt_tokens": 0, "completion_tokens": 0}}})


if __name__ == "__main__":
    load_state()
    host, port = os.environ.get("MOCK_DIFY_HOST", "0.0.0.0"), int(os.environ.get("MOCK_DIFY_PORT", "8080"))
    print("=" * 70 + "\nDIFY SIMULADO (mock): solo para pruebas y demos. No hay modelo real.\n" + "=" * 70, flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()
