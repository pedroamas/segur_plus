"""Orquesta la generación de los 32 documentos a partir de la BD (fuente de verdad) y del YAML de permisos."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

from . import docs_direccion as dd, docs_empleados as de, docs_finanzas as df, docs_generales as dg
from .catalog import EXT, PER_EMPLOYEE, title_and_type
from .facts import Facts
from .spec import doc_specs

CORPORATE_BUILDERS = {"D01": dd.build_d01, "D02": dd.build_d02, "D03": dd.build_d03, "D04": dd.build_d04, "D05": dd.build_d05, "D06": dd.build_d06,
                      "D07": df.build_d07, "D08": df.build_d08, "D09": df.build_d09, "D10": df.build_d10, "D11": df.build_d11, "D12": df.build_d12,
                      "D13": df.build_d13}


@dataclass(frozen=True)
class Generated:
    code: str
    title: str
    file_type: str
    path: Path
    sha256: str
    size: int


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_all(facts: Facts, out_dir: Path) -> list[Generated]:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    names = {c: u.full_name for c, u in facts.users.items()}
    result = []
    for sp in doc_specs():
        title, ftype = title_and_type(sp.code, names)
        path = out_dir / f"{sp.code}.{EXT[ftype]}"
        if sp.code in CORPORATE_BUILDERS:
            CORPORATE_BUILDERS[sp.code](facts, path)
        elif sp.code in PER_EMPLOYEE:
            kind = PER_EMPLOYEE[sp.code][1]
            {"contract": de.build_contract, "activity": de.build_activity, "payroll": de.build_payroll}[kind](facts, sp.code, path)
        else:
            dg.build_text_doc(sp.code, path)
        result.append(Generated(sp.code, title, ftype, path, sha256_of(path), path.stat().st_size))
    manifest = [{"code": g.code, "title": g.title, "file": g.path.name, "file_type": g.file_type, "sha256": g.sha256, "size": g.size} for g in result]
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result
