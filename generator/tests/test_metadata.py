"""documents ↔ permissions-spec.yaml (nada hardcodeado en el generador) y objetos en MinIO con las claves/checksums del seed."""
import hashlib

import pytest

from segurplus_gen import config, storage
from segurplus_gen.spec import doc_specs


def test_documents_table_matches_yaml(conn):
    rows = {r[0]: r for r in conn.execute("""SELECT d.code, d.kb, d.classification, d.category, o.employee_code, d.allowed_roles, d.index_status,
                                                    d.storage_key, d.dify_document_id, c.employee_code
                                             FROM documents d LEFT JOIN users o ON o.id = d.owner_id JOIN users c ON c.id = d.created_by""")}
    assert len(rows) == 32
    for s in doc_specs():
        code, kb, cls, cat, owner, roles, status, key, dify, creator = rows[s.code]
        assert (kb, cls, cat, owner, set(roles)) == (s.kb, s.cls, s.cat, s.owner, set(s.allowed_roles))
        assert status == "PENDING" and dify is None and creator == "DIR001"
        assert key.startswith("documents/") and key.endswith((".pdf", ".xlsx", ".csv", ".json"))


@pytest.mark.live
def test_minio_objects_match_checksums(conn):
    c = storage.client()
    objs = {o.object_name for o in c.list_objects(config.BUCKET, prefix=config.KEY_PREFIX, recursive=True)}
    rows = conn.execute("SELECT storage_key, checksum_sha256, size_bytes FROM documents").fetchall()
    assert objs == {r[0] for r in rows}                  # ni fixtures ni subidas de prueba sobrantes
    for key, checksum, size in rows:
        data = c.get_object(config.BUCKET, key).read()
        assert hashlib.sha256(data).hexdigest() == checksum and len(data) == size
