"""Carga de permissions-spec.yaml (fuente única de código, kb, clasificación, categoría, propietario y roles)."""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

import yaml

from .config import SPEC_PATH


@dataclass(frozen=True)
class DocSpec:
    code: str
    kb: str
    cls: str
    cat: str
    owner: str | None
    allowed_roles: tuple[str, ...]


@lru_cache(maxsize=1)
def load_spec() -> dict:
    with open(SPEC_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f)


def doc_specs() -> list[DocSpec]:
    return [DocSpec(d["code"], d["kb"], d["cls"], d["cat"], d["owner"], tuple(d["allowed_roles"]))
            for d in load_spec()["documents"]]


def doc_spec(code: str) -> DocSpec:
    return next(d for d in doc_specs() if d.code == code)
