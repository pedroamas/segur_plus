"""Libros XLSX deterministas con fórmulas reales y valores en caché.

openpyxl escribe las fórmulas sin valor calculado (`<v/>`) y marca la fecha actual en el zip y en docProps. Aquí:
1) propiedades fijas; 2) un evaluador mínimo (SUM, SUMIF, ROUND, AVERAGE, aritmética) calcula cada fórmula y se inyecta
como valor en caché (lo leen pandas/openpyxl `data_only` y la ingesta de Dify; Excel/POI recalculan igualmente);
3) el zip se reescribe con orden y marcas de tiempo fijos => mismo SHA-256 en cada ejecución.
"""
from __future__ import annotations

import io
import re
import zipfile
from datetime import datetime
from decimal import Decimal

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import column_index_from_string, get_column_letter

FIXED_DT = datetime(2027, 1, 15, 9, 0, 0)
NAVY, LIGHT, TOTAL = "12355B", "EEF2F6", "DCE6F0"
EUR = '#,##0.00 "€";[Red]-#,##0.00 "€"'
INT = "#,##0"
PCT = "0.0%"
DATE = "dd/mm/yyyy"
thin = Side(style="thin", color="B8C4D0")


def new_book(title: str, creator: str = "SegurPlus") -> Workbook:
    wb = Workbook()
    wb.remove(wb.active)
    wb.properties.creator = creator
    wb.properties.title = title
    wb.properties.created = FIXED_DT
    wb.properties.modified = FIXED_DT
    wb.properties.lastModifiedBy = creator
    wb.calculation.fullCalcOnLoad = True
    return wb


def header(ws, row: int, labels: list[str], first_col: int = 1) -> None:
    for i, label in enumerate(labels):
        c = ws.cell(row=row, column=first_col + i, value=label)
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor=NAVY)
        c.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        c.border = Border(bottom=thin)


def title(ws, text: str, subtitle: str | None = None) -> int:
    ws["A1"] = text
    ws["A1"].font = Font(bold=True, size=14, color=NAVY)
    if subtitle:
        ws["A2"] = subtitle
        ws["A2"].font = Font(italic=True, color="5B6770")
    return 4


def total_style(ws, row: int, first_col: int, last_col: int) -> None:
    for col in range(first_col, last_col + 1):
        c = ws.cell(row=row, column=col)
        c.font = Font(bold=True)
        c.fill = PatternFill("solid", fgColor=TOTAL)
        c.border = Border(top=thin)


def fmt_col(ws, col: int, first: int, last: int, number_format: str) -> None:
    for r in range(first, last + 1):
        ws.cell(row=r, column=col).number_format = number_format


def widths(ws, values: list[float]) -> None:
    for i, w in enumerate(values, 1):
        ws.column_dimensions[get_column_letter(i)].width = w


def put(ws, ref: str, value, number_format: str | None = None, bold: bool = False):
    if isinstance(value, Decimal):
        value = float(value)
    ws[ref] = value
    if number_format:
        ws[ref].number_format = number_format
    if bold:
        ws[ref].font = Font(bold=True)
    return ws[ref]


def row_values(ws, row: int, values: list, formats: dict[int, str] | None = None, first_col: int = 1) -> None:
    for i, v in enumerate(values):
        if isinstance(v, Decimal):
            v = float(v)
        c = ws.cell(row=row, column=first_col + i, value=v)
        if formats and (first_col + i) in formats:
            c.number_format = formats[first_col + i]


# --- evaluador de fórmulas ---------------------------------------------------------------------------------------
_REF = re.compile(r"(?:(?:'([^']+)'|([A-Za-z_][A-Za-z0-9_]*))!)?\$?([A-Z]{1,3})\$?(\d+)(?::\$?([A-Z]{1,3})\$?(\d+))?")


class _Eval:
    def __init__(self, wb: Workbook):
        self.wb = wb
        self.cache: dict[tuple[str, str], float] = {}

    def cell(self, sheet: str, ref: str) -> float:
        key = (sheet, ref)
        if key not in self.cache:
            v = self.wb[sheet][ref].value
            if isinstance(v, str) and v.startswith("="):
                self.cache[key] = self.formula(sheet, v[1:])
            elif isinstance(v, (int, float, str)):
                self.cache[key] = v
            else:
                self.cache[key] = 0
        return self.cache[key]

    def rng(self, sheet, a, b):
        ws = self.wb[sheet]
        (c1, r1), (c2, r2) = _split(a), _split(b)
        return [[ws.cell(row=r, column=c) for c in range(c1, c2 + 1)] for r in range(r1, r2 + 1)]

    def formula(self, sheet: str, expr: str) -> float:
        parts = re.split(r'("[^"]*")', expr)
        out = []
        for part in parts:
            if part.startswith('"'):
                out.append(repr(part[1:-1]))
                continue

            def sub(m):
                sh = m.group(1) or m.group(2) or sheet
                a = f"{m.group(3)}{m.group(4)}"
                if m.group(5):
                    return f"_R({sh!r},{a!r},{m.group(5) + m.group(6)!r})"
                return f"_C({sh!r},{a!r})"
            out.append(_REF.sub(sub, part))
        py = "".join(out)

        def flat(args):
            for a in args:
                if isinstance(a, list):
                    for row in a:
                        for c in row:
                            yield c
                else:
                    yield a

        def val(c):
            if hasattr(c, "value"):
                v = c.value
                if isinstance(v, str) and v.startswith("="):
                    return self.cell(c.parent.title, c.coordinate)
                return v if isinstance(v, (int, float)) else 0
            return c

        env = {
            "_C": self.cell,
            "_R": lambda sh, a, b: self.rng(sh, a, b),
            "SUM": lambda *a: sum(val(c) for c in flat(a)),
            "AVERAGE": lambda *a: (lambda xs: sum(xs) / len(xs) if xs else 0)([val(c) for c in flat(a)]),
            "ROUND": lambda x, n=0: float(Decimal(repr(x)).quantize(Decimal(1).scaleb(-int(n)), rounding="ROUND_HALF_UP")),
            "SUMIF": self._sumif,
            "COUNTIF": lambda rg, crit: sum(1 for c in flat([rg]) if c.value == crit),
            "MAX": lambda *a: max(val(c) for c in flat(a)),
            "MIN": lambda *a: min(val(c) for c in flat(a)),
        }
        return eval(py, {"__builtins__": {}}, env)

    def _sumif(self, rg, crit, sum_rg=None):
        crit_cells = [c for row in rg for c in row]
        sum_cells = [c for row in (sum_rg or rg) for c in row]
        total = 0
        for c, s in zip(crit_cells, sum_cells):
            if c.value == crit:
                v = s.value
                total += self.cell(s.parent.title, s.coordinate) if isinstance(v, str) and v.startswith("=") else (v if isinstance(v, (int, float)) else 0)
        return total


def _split(ref: str) -> tuple[int, int]:
    m = re.match(r"([A-Z]+)(\d+)", ref)
    return column_index_from_string(m.group(1)), int(m.group(2))


def save(wb: Workbook, path) -> None:
    """Guarda de forma determinista, con valores en caché para cada fórmula."""
    ev = _Eval(wb)
    cached: dict[str, dict[str, float]] = {}
    for ws in wb.worksheets:
        for row in ws.iter_rows():
            for c in row:
                if isinstance(c.value, str) and c.value.startswith("="):
                    cached.setdefault(ws.title, {})[c.coordinate] = ev.cell(ws.title, c.coordinate)
    raw = io.BytesIO()
    wb.save(raw)
    zin = zipfile.ZipFile(io.BytesIO(raw.getvalue()))
    order = {ws.title: f"xl/worksheets/sheet{i}.xml" for i, ws in enumerate(wb.worksheets, 1)}
    by_file = {order[t]: vals for t, vals in cached.items()}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for name in sorted(zin.namelist()):
            data = zin.read(name)
            if name == "docProps/core.xml":    # openpyxl sobrescribe `modified` con la hora actual al guardar
                data = re.sub(rb"(<dcterms:modified[^>]*>)[^<]*", rb"\g<1>2027-01-15T09:00:00Z", data)
            if name in by_file:
                text = data.decode("utf-8")
                for ref, v in by_file[name].items():
                    text = re.sub(rf'(<c r="{ref}"[^>]*><f>.*?</f>)<v ?/>', lambda m: f"{m.group(1)}<v>{_num(v)}</v>", text, count=1)
                data = text.encode("utf-8")
            info = zipfile.ZipInfo(name, date_time=(2027, 1, 15, 9, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            zout.writestr(info, data)


def _num(v: float) -> str:
    return repr(round(float(v), 10)) if not float(v).is_integer() else str(int(v))
