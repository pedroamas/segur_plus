"""Utilidades de los tests de consistencia: extracción de cifras de los ficheros generados y consultas SQL independientes del generador."""
from __future__ import annotations

import csv
import io
import json
import re
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import openpyxl
import pypdf

C = Decimal("0.01")
OCT = date(2026, 10, 1)
MONTHS = [date(2026, m, 1) for m in (9, 10, 11, 12)]


def q2(x) -> Decimal:
    return Decimal(x).quantize(C, rounding=ROUND_HALF_UP)


def parse_num(s: str) -> Decimal:
    return Decimal(s.replace("€", "").replace("%", "").strip().replace(".", "").replace(",", "."))


def pdf_lines(path: Path) -> list[str]:
    reader = pypdf.PdfReader(str(path))
    return [ln.strip() for page in reader.pages for ln in page.extract_text().splitlines() if ln.strip()]


def after(lines: list[str], label: str, n: int = 1, occurrence: int = 0) -> list[str]:
    idx = [i for i, ln in enumerate(lines) if ln == label]
    assert len(idx) > occurrence, f"etiqueta no encontrada: {label!r}"
    i = idx[occurrence]
    return lines[i + 1:i + 1 + n]


def value(lines, label, occurrence=0) -> Decimal:
    return parse_num(after(lines, label, 1, occurrence)[0])


def sheet_rows(path: Path, sheet: str):
    wb = openpyxl.load_workbook(path, data_only=True)
    return [list(r) for r in wb[sheet].iter_rows(values_only=True)]


def find_row(rows, first_cell, col=0):
    return next(r for r in rows if r[col] == first_cell)


def csv_rows(path: Path, delimiter=","):
    return list(csv.DictReader(io.StringIO(path.read_text(encoding="utf-8")), delimiter=delimiter))


def json_doc(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


# --- SQL independiente ---------------------------------------------------------------------------------------------
def month_bounds(m: date):
    nxt = date(m.year + (m.month == 12), m.month % 12 + 1, 1)
    return m, nxt


def sql_one(conn, sql, *p):
    return conn.execute(sql, p).fetchone()[0]


def sold_count(conn, m, emp=None):
    a, b = month_bounds(m)
    return sql_one(conn, """SELECT count(*) FROM policies p JOIN users u ON u.id=p.employee_id
                            WHERE p.sale_date >= %s AND p.sale_date < %s AND (%s::text IS NULL OR u.employee_code=%s)""", a, b, emp, emp)


def sold_premium(conn, m, emp=None):
    a, b = month_bounds(m)
    return sql_one(conn, """SELECT coalesce(sum(p.premium),0) FROM policies p JOIN users u ON u.id=p.employee_id
                            WHERE p.sale_date >= %s AND p.sale_date < %s AND (%s::text IS NULL OR u.employee_code=%s)""", a, b, emp, emp)


def insurer_commission(conn, m):
    a, b = month_bounds(m)
    return sql_one(conn, "SELECT coalesce(sum(insurer_commission_amount),0) FROM policies WHERE sale_date >= %s AND sale_date < %s", a, b)


def commission(conn, m, kind, emp=None):
    return sql_one(conn, """SELECT coalesce(sum(c.amount),0) FROM commissions c JOIN users u ON u.id=c.employee_id
                            WHERE c.kind=%s AND c.period_month=%s AND (%s::text IS NULL OR u.employee_code=%s)""", kind, m, emp, emp)


def target(conn, m, emp=None):
    return sql_one(conn, """SELECT coalesce(sum(t.target_amount),0) FROM sales_targets t JOIN users u ON u.id=t.employee_id
                            WHERE t.period_month=%s AND (%s::text IS NULL OR u.employee_code=%s)""", m, emp, emp)


def attainment(conn, m, emp=None):
    return (sold_premium(conn, m, emp) * 100 / target(conn, m, emp)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)


def expenses(conn, m, category=None):
    a, b = month_bounds(m)
    return sql_one(conn, """SELECT coalesce(sum(amount),0) FROM expenses WHERE expense_date >= %s AND expense_date < %s
                            AND (%s::text IS NULL OR category=%s)""", a, b, category, category)


IRPF = {"MANAGER": Decimal("0.22"), "ACCOUNTANT": Decimal("0.17"), "SALES_MANAGER": Decimal("0.17"), "EMPLOYEE": Decimal("0.13")}
SS_WORKER, SS_COMPANY = Decimal("0.0648"), Decimal("0.3160")


def payroll(conn, m):
    """Nómina por usuario con las reglas declaradas (generator/README.md): bruto = sueldo + comisiones netas."""
    out = {}
    for code, role, salary in conn.execute("SELECT employee_code, role, monthly_salary FROM users ORDER BY 1").fetchall():
        gross = q2(salary + commission(conn, m, "ACCRUAL", code) + commission(conn, m, "CLAWBACK", code))
        ssw, irpf = q2(gross * SS_WORKER), q2(gross * IRPF[role])
        out[code] = dict(salary=salary, accrual=commission(conn, m, "ACCRUAL", code), clawback=commission(conn, m, "CLAWBACK", code),
                         gross=gross, ssw=ssw, irpf=irpf, net=q2(gross - ssw - irpf), ssc=q2(gross * SS_COMPANY))
    return out
