"""Subida a MinIO con las mismas convenciones que el backend (bucket privado, clave `documents/<uuid>.<ext>`)."""
from __future__ import annotations

import io
from pathlib import Path

from minio import Minio

from . import config
from .catalog import EXT

CONTENT_TYPES = {"PDF": "application/pdf", "XLSX": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "CSV": "text/csv; charset=UTF-8", "JSON": "application/json"}


def client() -> Minio:
    s = config.minio_settings()
    return Minio(s["endpoint"], access_key=s["access_key"], secret_key=s["secret_key"], secure=s["secure"])


def storage_key(doc_id, file_type: str) -> str:
    return f"{config.KEY_PREFIX}{doc_id}.{EXT[file_type]}"


def ensure_bucket(c: Minio) -> None:
    if not c.bucket_exists(config.BUCKET):
        c.make_bucket(config.BUCKET)          # privado: sin política pública


def put_file(c: Minio, key: str, path: Path, file_type: str) -> None:
    data = path.read_bytes()
    c.put_object(config.BUCKET, key, io.BytesIO(data), len(data), content_type=CONTENT_TYPES[file_type])


def prune(c: Minio, keep: set[str]) -> list[str]:
    """Borra los objetos de documentos que ya no están en la BD (fixtures de F1, subidas de prueba)."""
    removed = []
    for obj in c.list_objects(config.BUCKET, prefix=config.KEY_PREFIX, recursive=True):
        if obj.object_name not in keep:
            c.remove_object(config.BUCKET, obj.object_name)
            removed.append(obj.object_name)
    return removed
