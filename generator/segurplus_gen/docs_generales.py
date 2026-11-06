"""D26-D29 (manuales de seguros) y G01-G03 (políticas generales): contenido en texts_manuales.py / texts_generales.py."""
from __future__ import annotations

from .pdfkit import Report
from .spec import doc_spec
from .texts_generales import GENERALES
from .texts_manuales import MANUALES


def _render(code: str, spec: dict, path) -> None:
    sp = doc_spec(code)
    r = Report(spec["title"], spec["subtitle"], code=code, classification=sp.cls, version=spec["version"], date_str=spec["date"], author="SegurPlus")
    for s in spec["sections"]:
        r.h1(s["h"])
        for para in s.get("p", []):
            r.p(para)
        if s.get("bullets"):
            r.bullets(s["bullets"])
        if s.get("table"):
            t = s["table"]
            r.table(t["head"], t["rows"], t.get("widths"), align_right_from=9)
        for sub in s.get("h2", []):
            r.h2(sub["h"])
            for para in sub.get("p", []):
                r.p(para)
            if sub.get("bullets"):
                r.bullets(sub["bullets"])
    r.build(path)


def build_text_doc(code: str, path) -> None:
    _render(code, {**MANUALES, **GENERALES}[code], path)
