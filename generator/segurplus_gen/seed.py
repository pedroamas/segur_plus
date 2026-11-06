"""Comando de administración: resetea e inserta el dataset, genera los 32 documentos, los sube a MinIO y registra `documents`.

    python -m segurplus_gen.seed                      # todo (BD + ficheros + MinIO + documents)
    python -m segurplus_gen.seed --out DIR            # directorio de ficheros (por defecto generator/out)
    python -m segurplus_gen.seed --files-only --out D # solo generar ficheros desde la BD actual (determinismo)

Idempotente: ids derivados (uuid5) y marcas de tiempo del reloj de negocio => ejecutarlo dos veces deja el mismo estado.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from . import config, db, storage
from .adversarial import build_adversarial
from .build import Generated, build_all
from .catalog import PER_EMPLOYEE
from .dataset import build_dataset, uid
from .facts import Facts
from .spec import doc_specs


def register_documents(conn, generated: list[Generated]) -> list[str]:
    users = db.user_ids(conn)
    specs = {s.code: s for s in doc_specs()}
    keys = []
    rows = []
    for g in generated:
        s = specs[g.code]
        doc_id = uid("document", g.code)
        key = storage.storage_key(doc_id, g.file_type)
        keys.append(key)
        rows.append((doc_id, g.code, g.title, g.file_type, s.cat, s.kb, s.cls, users[s.owner] if s.owner else None, list(s.allowed_roles),
                     key, g.sha256, g.size, "PENDING", users["DIR001"], config.SEED_TS, config.SEED_TS))
    with conn.transaction():
        conn.execute("DELETE FROM documents")      # sustituye fixtures de F1 y subidas de prueba (auditoría y conversaciones no se tocan)
        conn.cursor().executemany(
            """INSERT INTO documents (id, code, title, file_type, category, kb, classification, owner_id, allowed_roles, storage_key,
               checksum_sha256, size_bytes, index_status, created_by, created_at, updated_at)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", rows)
    return keys


def seed(out_dir: Path, upload: bool = True) -> list[Generated]:
    conn = db.connect()
    db.load_dataset(conn, build_dataset())
    generated = build_all(Facts(conn), out_dir)
    build_adversarial(config.GENERATOR / "adversarial")
    if upload:
        mc = storage.client()
        storage.ensure_bucket(mc)
        for g in generated:
            storage.put_file(mc, storage.storage_key(uid("document", g.code), g.file_type), g.path, g.file_type)
    keys = register_documents(conn, generated)
    if upload:
        removed = storage.prune(mc, set(keys))
        print(f"MinIO: {len(keys)} objetos subidos, {len(removed)} obsoletos eliminados")
    conn.close()
    return generated


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=config.DEFAULT_OUT)
    ap.add_argument("--files-only", action="store_true", help="solo genera ficheros desde la BD actual (no resetea ni sube)")
    ap.add_argument("--skip-upload", action="store_true", help="BD y ficheros, sin MinIO (los documents quedarían sin objeto: solo pruebas)")
    ap.add_argument("--adversarial-only", action="store_true")
    a = ap.parse_args()
    if a.adversarial_only:
        build_adversarial(config.GENERATOR / "adversarial")
        return
    if a.files_only:
        conn = db.connect()
        gen = build_all(Facts(conn), a.out)
        conn.close()
    else:
        gen = seed(a.out, upload=not a.skip_upload)
    print(f"{len(gen)} documentos en {a.out}")
    for g in gen:
        print(f"  {g.code}  {g.sha256}  {g.size:>7}  {g.path.name}")


if __name__ == "__main__":
    main()
