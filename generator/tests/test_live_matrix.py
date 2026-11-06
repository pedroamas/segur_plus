"""Matriz usuario x documento (224 celdas, READ y DOWNLOAD por REST) contra la instancia viva (:8100) y el oráculo del YAML.

Requiere el stack de desarrollo arriba y sembrado (`python -m segurplus_gen.seed`). La contraseña de dev sale de deploy/.env
(SEGURPLUS_SEED_DEFAULT_PASSWORD) y nunca se imprime.
"""
import hashlib
import os
from collections import Counter

import httpx
import pytest

from segurplus_gen import config
from segurplus_gen.spec import doc_specs, load_spec

pytestmark = pytest.mark.live
BASE = os.environ.get("SEGURPLUS_API", "http://localhost:8100/api/v1")


def oracle(user_code: str, spec) -> bool:
    """Oráculo independiente de AccessPolicyService: rol in allowed_roles o propietario."""
    role = load_spec()["users"][user_code]["role"]
    return role in spec.allowed_roles or spec.owner == user_code


@pytest.fixture(scope="module")
def api():
    return httpx.Client(base_url=BASE, timeout=30)


@pytest.fixture(scope="module")
def tokens(api):
    pw = config.load_env()["SEGURPLUS_SEED_DEFAULT_PASSWORD"]
    out = {}
    for code in load_spec()["users"]:
        r = api.post("/auth/login", json={"username": code, "password": pw})
        assert r.status_code == 200, f"login {code}"
        out[code] = {"Authorization": "Bearer " + r.json()["accessToken"]}
    return out


@pytest.fixture(scope="module")
def doc_ids(conn):
    return {code: (str(i), cs) for code, i, cs in conn.execute("SELECT code, id, checksum_sha256 FROM documents WHERE deleted_at IS NULL")}


def test_224_cells_read_and_download_match_oracle(api, tokens, doc_ids):
    specs = doc_specs()
    assert len(specs) == 32 and set(doc_ids) == {s.code for s in specs}
    cells = diffs = allowed_cells = 0
    for user, headers in tokens.items():
        for s in specs:
            doc_id, checksum = doc_ids[s.code]
            expected = oracle(user, s)
            cells += 1
            allowed_cells += expected
            read = api.get(f"/documents/{doc_id}", headers=headers)
            dl = api.get(f"/documents/{doc_id}/download", headers=headers)
            want = 200 if expected else 404
            if read.status_code != want or dl.status_code != want:
                diffs += 1
                print(f"DIFF {user} {s.code}: read={read.status_code} download={dl.status_code} esperado={want}")
            elif expected:
                assert hashlib.sha256(dl.content).hexdigest() == checksum, f"checksum de {s.code} descargado por {user}"
    assert cells == 224
    assert diffs == 0
    assert allowed_cells == 99          # 32 + 14 + 13 + 4*10 (DECISIONES: validación de la matriz)


def test_listing_per_user_equals_oracle(api, tokens):
    for user, headers in tokens.items():
        codes = set()
        page = 0
        while True:
            body = api.get(f"/documents?size=50&page={page}", headers=headers).json()
            codes |= {i["code"] for i in body["items"]}
            if len(codes) >= 32 or not body["items"] or len(body["items"]) < 50:
                break
            page += 1
        assert codes == {s.code for s in doc_specs() if oracle(user, s)}, user


def test_kb_counts_general7_direccion6_finanzas7_empleados12(api, tokens, conn):
    items = api.get("/documents?size=50", headers=tokens["DIR001"]).json()["items"]
    assert len(items) == 32
    assert Counter(i["kb"] for i in items) == {"GENERAL": 7, "DIRECCION": 6, "FINANZAS": 7, "EMPLEADOS": 12}
    db_counts = dict(conn.execute("SELECT kb, count(*) FROM documents WHERE deleted_at IS NULL GROUP BY kb").fetchall())
    assert db_counts == {"GENERAL": 7, "DIRECCION": 6, "FINANZAS": 7, "EMPLEADOS": 12}


def test_view_and_preview_work_on_real_files(api, tokens, doc_ids):
    h = tokens["DIR001"]
    assert api.get(f"/documents/{doc_ids['D01'][0]}/view", headers=h).status_code == 200
    r = api.get(f"/documents/{doc_ids['D07'][0]}/preview", headers=h)
    assert r.status_code == 200 and r.json()


def test_monthly_salary_never_exposed_live(api, tokens):
    for headers in tokens.values():
        for path in ("/users/me", "/documents?size=50"):
            assert "salary" not in api.get(path, headers=headers).text.lower()
    assert "salary" not in httpx.get(BASE.replace("/api/v1", "") + "/v3/api-docs", timeout=30).text.lower()
