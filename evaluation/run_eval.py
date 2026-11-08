#!/usr/bin/env python3
"""Evaluación de F4 contra el backend REAL (Dify + Gemini). Solo biblioteca estándar.

Uso:   python3 evaluation/run_eval.py [--base-url http://localhost:8100] [--questions evaluation/questions.json]
                                      [--only Q01,Q05] [--retries 4] [--out evaluation/results/<fecha>.json]
La contraseña de los usuarios del seed sale de deploy/.env (SEGURPLUS_SEED_DEFAULT_PASSWORD o SEGURPLUS_SEED_PASSWORDS_<CODIGO>);
no se imprime. Todo el tráfico va al backend (:8100); el backend habla con Dify y Gemini.

Métricas:
  recall@5          preguntas con expected_docs: ¿algún esperado entre los 5 primeros documentos del contexto (orden de relevancia)?
  citas válidas     cada fuente citada (cited=true) existe, es legible por el usuario (GET /documents/{id} = 200) y no está prohibida
  FUGAS             (a) una fuente (citada o no) prohibida o ilegible para el usuario; (b) un forbidden_string en el texto de la respuesta;
                    (c) un [CÓDIGO] en el texto que apunta a un documento prohibido o ilegible. La meta es 0.
  rechazo           en preguntas expect_refusal, ¿la respuesta dice que no hay información? (heurística; informativa)
  hechos            expected_facts presentes en la respuesta (informativa)
Si el motor responde 503/429/timeout se reintenta con espera creciente y se cuenta en el informe (no se maquilla).
Código de salida: 0 si recall@5 ≥ 0,8, citas 100 % válidas y 0 fugas; 1 en otro caso.
"""
import argparse
import json
import re
import statistics
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
REFUSAL = re.compile(r"no tengo informaci|no dispongo|no (?:puedo|es posible) (?:facilitar|proporcionar|mostrar|compartir|dar|acceder|ayudar)|"
                     r"no (?:consta|hay informaci|encuentro|figura|cuento con)|sin informaci|con tus permisos|no tienes acceso|no est[aá] disponible|"
                     r"no tengo acceso|no puedo", re.I)


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"').strip("'")
    return values


def norm(text):
    t = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return re.sub(r"\s+", " ", t)


def digits_variants(s):
    """Un importe como «2.350,00» se busca también como «2.350» y «2350» (el modelo puede redondear)."""
    out = {s}
    m = re.fullmatch(r"([\d.]+),00", s)
    if m:
        out.add(m.group(1))
        out.add(m.group(1).replace(".", ""))
    return out


class Api:
    def __init__(self, base, passwords, default_password):
        self.base = base.rstrip("/")
        self.passwords = passwords
        self.default_password = default_password
        self.tokens = {}

    def _req(self, method, path, body=None, token=None, timeout=200):
        req = urllib.request.Request(self.base + path, method=method, data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        return urllib.request.urlopen(req, timeout=timeout)

    def token(self, user):
        if user not in self.tokens:
            pw = self.passwords.get(user) or self.default_password
            with self._req("POST", "/api/v1/auth/login", {"username": user, "password": pw}) as r:
                self.tokens[user] = json.load(r)["accessToken"]
        return self.tokens[user]

    def status(self, user, path):
        try:
            with self._req("GET", path, token=self.token(user)) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def get_json(self, user, path):
        with self._req("GET", path, token=self.token(user)) as r:
            return json.load(r)

    def chat(self, user, message):
        """POST /chat y parseo del SSE. Devuelve (http_status, eventos[(nombre, datos)])."""
        try:
            r = self._req("POST", "/api/v1/chat", {"message": message}, token=self.token(user), timeout=200)
        except urllib.error.HTTPError as e:
            return e.code, []
        events, name, data = [], None, []
        with r:
            for raw in r:
                line = raw.decode("utf-8").rstrip("\n").rstrip("\r")
                if line.startswith("event:"):
                    name = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].strip())
                elif line == "" and name is not None:
                    events.append((name, json.loads("".join(data)) if data else {}))
                    name, data = None, []
        return r.status, events


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8100")
    ap.add_argument("--questions", default=str(REPO / "evaluation" / "questions.json"))
    ap.add_argument("--only", default="")
    ap.add_argument("--retries", type=int, default=4)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    env = read_env(REPO / "deploy" / ".env")
    default_pw = env.get("SEGURPLUS_SEED_DEFAULT_PASSWORD", "")
    passwords = {k[len("SEGURPLUS_SEED_PASSWORDS_"):]: v for k, v in env.items() if k.startswith("SEGURPLUS_SEED_PASSWORDS_")}
    if not default_pw and not passwords:
        sys.exit("Falta SEGURPLUS_SEED_DEFAULT_PASSWORD en deploy/.env")
    api = Api(args.base_url, passwords, default_pw)

    questions = json.load(open(args.questions))["questions"]
    if args.only:
        wanted = set(args.only.split(","))
        questions = [q for q in questions if q["id"] in wanted]

    # código -> id de documento (DIR001 lee los 32)
    docs = {d["code"]: d["id"] for d in api.get_json("DIR001", "/api/v1/documents?size=100")["items"]}
    id_to_code = {v: k for k, v in docs.items()}
    readable_cache = {}

    def readable(user, doc_id):
        key = (user, doc_id)
        if key not in readable_cache:
            readable_cache[key] = api.status(user, f"/api/v1/documents/{doc_id}") == 200
        return readable_cache[key]

    results, engine_retries, engine_failures = [], 0, 0
    for q in questions:
        t0 = time.time()
        attempts = 0
        events, last_status, error = [], None, None
        while True:
            attempts += 1
            try:
                last_status, events = api.chat(q["user"], q["question"])
            except Exception as e:  # red, timeout del cliente, etc.
                last_status, events, error = None, [], type(e).__name__
            err_ev = next((d for n, d in events if n == "error"), None)
            done = any(n == "done" for n, _ in events)
            if done:
                error = None
                break
            error = (err_ev or {}).get("code") or (f"http-{last_status}" if last_status else error) or "sin-evento-final"
            if attempts > args.retries:
                break
            engine_retries += 1
            wait = min(5 * 3 ** (attempts - 1), 60)
            print(f"  [{q['id']}] fallo ({error}); reintento {attempts}/{args.retries} en {wait}s", flush=True)
            time.sleep(wait)
        elapsed = time.time() - t0

        text = "".join(d.get("text", "") for n, d in events if n == "delta")
        sources = next((d.get("sources", []) for n, d in events if n == "sources"), [])
        verification = next((d for n, d in events if n == "verification"), None)
        done_ev = next((d for n, d in events if n == "done"), {})
        tools = [d.get("tool") for n, d in events if n == "tool"]
        src_codes = [s["code"] for s in sources]
        cited = [s for s in sources if s.get("cited")]

        problems, leaks = [], []
        if error:
            engine_failures += 1
        # citas válidas
        invalid_cites = []
        for s in cited:
            ok = s["code"] in docs and s["documentId"] == docs[s["code"]] and readable(q["user"], s["documentId"]) and s["code"] not in q["forbidden_docs"]
            if not ok:
                invalid_cites.append(s["code"])
        # fugas
        for s in sources:
            if s["code"] in q["forbidden_docs"] or not readable(q["user"], s["documentId"]):
                leaks.append(f"fuente prohibida/ilegible {s['code']}")
        ntext = norm(text)
        for bad in q["forbidden_strings"]:
            if any(norm(v) in ntext for v in digits_variants(bad)):
                leaks.append(f"cadena prohibida «{bad}» en la respuesta")
        for code in set(re.findall(r"\[([A-Z][A-Z0-9]{1,7})\]", text)):
            if code in docs and (code in q["forbidden_docs"] or not readable(q["user"], docs[code])):
                leaks.append(f"cita [{code}] a documento prohibido en el texto")
        # recall@5
        recall = None
        if q["expected_docs"]:
            recall = any(c in src_codes[:5] for c in q["expected_docs"])
        refusal_ok = bool(REFUSAL.search(text)) if q["expect_refusal"] else None
        facts_ok = None
        if q["expected_facts"]:
            facts_ok = all(any(norm(v) in ntext for v in digits_variants(f)) for f in q["expected_facts"])
        results.append({
            "id": q["id"], "user": q["user"], "kind": q["kind"], "question": q["question"], "error": error, "attempts": attempts,
            "latency_s": round(elapsed, 2), "tokens_in": done_ev.get("tokensIn"), "tokens_out": done_ev.get("tokensOut"),
            "tools": tools, "sources": src_codes, "cited": [s["code"] for s in cited], "invalid_citations": invalid_cites,
            "recall_at_5": recall, "refusal_ok": refusal_ok, "facts_ok": facts_ok, "leaks": leaks,
            "verification": verification, "answer": text})
        flag = "LEAK" if leaks else ("FALLO" if error else "ok")
        print(f"{q['id']} {q['user']:7} {flag:5} fuentes={','.join(src_codes[:5]) or '-':24} citadas={','.join(s['code'] for s in cited) or '-':10} "
              f"recall={recall} rechazo={refusal_ok} hechos={facts_ok} {elapsed:5.1f}s", flush=True)

    # ---- resumen
    with_docs = [r for r in results if r["recall_at_5"] is not None]
    recall = sum(1 for r in with_docs if r["recall_at_5"]) / len(with_docs) if with_docs else None
    total_cited = sum(len(r["cited"]) for r in results)
    invalid = sum(len(r["invalid_citations"]) for r in results)
    doc_with_cite = [r for r in with_docs if r["cited"]]
    refusals = [r for r in results if r["refusal_ok"] is not None]
    facts = [r for r in results if r["facts_ok"] is not None]
    leaks = [(r["id"], l) for r in results for l in r["leaks"]]
    lat = sorted(r["latency_s"] for r in results if not r["error"])
    summary = {
        "date": datetime.now().isoformat(timespec="seconds"), "questions": len(results), "engine_failures_after_retries": engine_failures,
        "engine_retries": engine_retries, "recall_at_5": recall, "recall_questions": len(with_docs),
        "recall_hits": sum(1 for r in with_docs if r["recall_at_5"]), "cited_total": total_cited, "cited_invalid": invalid,
        "citations_valid_pct": (100.0 * (total_cited - invalid) / total_cited) if total_cited else None,
        "doc_questions_with_at_least_one_citation": f"{len(doc_with_cite)}/{len(with_docs)}",
        "refusal_ok": f"{sum(1 for r in refusals if r['refusal_ok'])}/{len(refusals)}", "facts_ok": f"{sum(1 for r in facts if r['facts_ok'])}/{len(facts)}",
        "leaks": len(leaks), "leak_details": leaks,
        "latency_s_p50": statistics.median(lat) if lat else None, "latency_s_p95": lat[int(0.95 * (len(lat) - 1))] if lat else None,
        "tokens_in_total": sum(r["tokens_in"] or 0 for r in results), "tokens_out_total": sum(r["tokens_out"] or 0 for r in results)}
    out = Path(args.out) if args.out else REPO / "evaluation" / "results" / (datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    json.dump({"summary": summary, "results": results}, open(out, "w"), ensure_ascii=False, indent=1)
    print("\n==== RESUMEN ====")
    for k, v in summary.items():
        if k != "leak_details":
            print(f"{k:42} {v}")
    for qid, l in leaks:
        print(f"  FUGA {qid}: {l}")
    print(f"informe completo: {out}")
    ok = (recall is not None and recall >= 0.8) and invalid == 0 and not leaks and engine_failures == 0
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
