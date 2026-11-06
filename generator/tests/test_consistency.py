"""Criterio F2 3: cada cifra de cada documento con cifras = cifra calculada en la BD.

Se generan los 32 ficheros en un directorio temporal, se extraen las cifras de los propios ficheros (PDF, XLSX con valores en
caché, CSV, JSON) y se comparan con consultas SQL independientes (helpers.py). Cubre D01, D03, D06-D13, D15-D16, D18-D19, D21-D22, D24-D25.
"""
from decimal import Decimal

import json
import openpyxl
import pytest

from segurplus_gen.build import build_all
from segurplus_gen.catalog import PER_EMPLOYEE
from segurplus_gen.fmt import eur, num
from tests.helpers import *  # noqa: F403

EMPS = ["EMP001", "EMP002", "EMP003", "EMP004"]
Z = Decimal("0")


@pytest.fixture(scope="module")
def files(facts, tmp_path_factory):
    return {g.code: g.path for g in build_all(facts, tmp_path_factory.mktemp("docs"))}


@pytest.fixture(scope="module")
def pay(conn):
    return payroll(conn, OCT)


def test_generated_files_are_the_seeded_ones(files, conn):
    """Los ficheros que se prueban son byte a byte los registrados en `documents` (checksum del seed)."""
    import hashlib
    db = dict(conn.execute("SELECT code, checksum_sha256 FROM documents").fetchall())
    assert {c: hashlib.sha256(p.read_bytes()).hexdigest() for c, p in files.items()} == db


def october_pnl(conn, pay, ss="agg"):
    gross = sum((p["gross"] for p in pay.values()), Z)
    ss_amt = q2(gross * SS_COMPANY) if ss == "agg" else sum((p["ssc"] for p in pay.values()), Z)
    rev, exp = insurer_commission(conn, OCT), expenses(conn, OCT)
    return rev, exp, gross, ss_amt, rev - exp - gross - ss_amt


def test_d01_monthly_management_report(files, conn, pay):
    t = pdf_lines(files["D01"])
    assert value(t, "Pólizas nuevas") == sold_count(conn, OCT) == 12
    assert value(t, "Primas emitidas") == sold_premium(conn, OCT)
    assert value(t, "Comisiones de empleados") == commission(conn, OCT, "ACCRUAL")
    assert value(t, "Cumplimiento de objetivos") == attainment(conn, OCT)
    for e in EMPS:
        label = next(ln for ln in t if ln.startswith(e + " ·"))
        n, prem, tgt, att, com = after(t, label, 5)
        assert (int(n), parse_num(prem), parse_num(tgt), parse_num(att), parse_num(com)) == (
            sold_count(conn, OCT, e), sold_premium(conn, OCT, e), target(conn, OCT, e), attainment(conn, OCT, e), commission(conn, OCT, "ACCRUAL", e))
    assert value(t, "Total gastos de explotación") == expenses(conn, OCT) == Decimal("7250.00")
    rev, exp, gross, ssc, res = october_pnl(conn, pay)
    assert parse_num(after(t, "Resultado del mes", 3)[1]) == res
    assert parse_num(after(t, "Sueldos y salarios (agregado, incl. comisiones netas)", 3)[1]) == gross


def test_d03_kpi_dashboard_json(files, conn):
    j = json_doc(files["D03"])
    for m in MONTHS:
        k = j["kpis_mensuales"][f"{m:%Y-%m}"]
        assert k["polizas_nuevas"] == sold_count(conn, m)
        assert Decimal(k["primas_eur"]) == sold_premium(conn, m)
        assert Decimal(k["objetivo_primas_eur"]) == target(conn, m)
        assert Decimal(k["cumplimiento_pct"]) == attainment(conn, m)
        assert Decimal(k["comisiones_empleados_devengadas_eur"]) == commission(conn, m, "ACCRUAL")
        assert Decimal(k["regularizaciones_clawback_eur"]) == commission(conn, m, "CLAWBACK")
        assert Decimal(k["ingresos_intermediacion_eur"]) == insurer_commission(conn, m)
        assert Decimal(k["gastos_explotacion_eur"]) == expenses(conn, m)
    for row in j["octubre_por_empleado"]:
        e = row["empleado"]
        assert (row["polizas"], Decimal(row["primas_eur"]), Decimal(row["objetivo_eur"]), Decimal(row["cumplimiento_pct"])) == (
            sold_count(conn, OCT, e), sold_premium(conn, OCT, e), target(conn, OCT, e), attainment(conn, OCT, e))
    assert "salary" not in json.dumps(j).lower() and "salario" not in json.dumps(j).lower().replace("sueldos_y_salarios_agregado", "")


def test_d06_commercial_performance(files, conn):
    t = pdf_lines(files["D06"])
    assert value(t, "Pólizas nuevas (octubre)") == 12 and value(t, "Primas (octubre)") == sold_premium(conn, OCT)
    assert value(t, "Cumplimiento (octubre)") == attainment(conn, OCT)
    assert value(t, "Pólizas totales sep-dic") == sql_one(conn, "SELECT count(*) FROM policies")
    for e in EMPS:
        label = next(ln for ln in t if ln.startswith(e + " ·"))
        n, prem, tgt, att, com = after(t, label, 5)
        assert (int(n), parse_num(prem), parse_num(att), parse_num(com)) == (sold_count(conn, OCT, e), sold_premium(conn, OCT, e), attainment(conn, OCT, e),
                                                                             commission(conn, OCT, "ACCRUAL", e))
    for m, name in zip(MONTHS, ["Septiembre", "Octubre", "Noviembre", "Diciembre"]):
        n, prem, tgt, att, _ = after(t, f"{name} de 2026", 5)
        assert (int(n), parse_num(prem), parse_num(tgt), parse_num(att)) == (sold_count(conn, m), sold_premium(conn, m), target(conn, m), attainment(conn, m))


def test_d07_invoices(files, conn):
    p = files["D07"]
    for sheet, direction in (("Emitidas", "ISSUED"), ("Recibidas", "RECEIVED")):
        rows = sheet_rows(p, sheet)
        data = [r for r in rows if isinstance(r[0], str) and r[0][:2] in ("FE", "IC", "NC", "AT", "OD", "AD", "SO", "GP", "BL", "EH", "FN", "PA") and r[2] and len(str(r[2])) == 7]
        assert len(data) == sql_one(conn, "SELECT count(*) FROM invoices WHERE direction=%s", direction)
        for m in MONTHS:
            sub = find_row(rows, f"Total {m:%Y-%m}")[5]
            assert q2(sub) == sql_one(conn, "SELECT coalesce(sum(amount),0) FROM invoices WHERE direction=%s AND period_month=%s", direction, m)
        assert q2(find_row(rows, "Total")[5]) == sql_one(conn, "SELECT sum(amount) FROM invoices WHERE direction=%s", direction)
        assert q2(find_row(rows, "Pendiente de cobro/pago (estado OPEN)")[5]) == sql_one(conn, "SELECT coalesce(sum(amount),0) FROM invoices WHERE direction=%s AND status='OPEN'", direction)
    res = sheet_rows(p, "Resumen")
    oct_row = find_row(res, "2026-10")
    assert q2(oct_row[1]) == insurer_commission(conn, OCT) and q2(oct_row[2]) == sql_one(conn, "SELECT sum(amount) FROM invoices WHERE direction='RECEIVED' AND period_month=%s", OCT)
    wb = openpyxl.load_workbook(p)
    assert str(wb["Resumen"]["B5"].value).startswith("=SUMIF")      # fórmulas reales


def test_d08_ledger_aggregated_payroll(files, conn, pay):
    p = files["D08"]
    rev, exp, gross, ssc, res = october_pnl(conn, pay)
    pl = sheet_rows(p, "Resultados")
    assert q2(find_row(pl, "705")[2]) == rev
    assert q2(sum(r[2] for r in pl if isinstance(r[0], str) and r[0].startswith("62"))) == -exp
    assert q2(find_row(pl, "640")[2]) == -gross and q2(find_row(pl, "642")[2]) == -ssc
    assert q2(find_row(pl, "Resultado del ejercicio (octubre)", 1)[2]) == res
    dia = sheet_rows(p, "Diario")
    tot = find_row(dia, "Totales", 4)
    assert q2(tot[5]) == q2(tot[6])
    may = sheet_rows(p, "Mayor")
    assert q2(find_row(may, "Totales")[5]) == 0
    # A5: nóminas solo agregadas; ningún sueldo individual en celdas ni nombres de empleados en el diario
    salaries = {q2(s) for (s,) in conn.execute("SELECT monthly_salary FROM users")}
    payroll_may = [r for r in may if r[0] in ("640", "642", "465", "476", "4751")]
    nums = {q2(c) for r in dia + payroll_may + pl for c in r if isinstance(c, (int, float)) and not isinstance(c, bool)}
    assert not (salaries & nums), "sueldos individuales visibles en el libro mayor"
    names = [n for (n,) in conn.execute("SELECT full_name FROM users")]
    assert not any(n in str(c) for r in dia for c in r if isinstance(c, str) for n in names)


def bank_rows(files):
    import csv, io
    return list(csv.DictReader(io.StringIO(files["D09"].read_text(encoding="utf-8")), delimiter=";"))


def test_d09_bank_statement(files, conn, pay):
    rows = bank_rows(files)
    num_ = lambda s: parse_num(s) if s else Z
    opening = num_(rows[0]["saldo"])
    closing = num_(rows[-1]["saldo"])
    moves = rows[1:-1]
    assert opening + sum(num_(r["abono"]) - num_(r["cargo"]) for r in moves) == closing
    receipts = sum(num_(r["abono"]) for r in moves if r["concepto"].startswith("Cobro comisiones"))
    expected = sql_one(conn, "SELECT coalesce(sum(amount),0) FROM invoices WHERE direction='ISSUED' AND status='PAID' AND due_date BETWEEN '2026-10-01' AND '2026-10-31'")
    assert receipts == expected - Decimal("200.06")         # FE-2026-0004 se abona el 02/11 (cobro en tránsito)
    assert sql_one(conn, "SELECT amount FROM invoices WHERE invoice_number='FE-2026-0004'") == Decimal("200.06")
    net = sum((p["net"] for p in pay.values()), Z)
    assert [num_(r["cargo"]) for r in moves if r["concepto"].startswith("Nóminas octubre")] == [net]
    # A5: solo una línea agregada de nóminas, sin importes individuales
    assert sum(1 for r in moves if "Nóminas" in r["concepto"]) == 1
    salaries = {q2(s) for (s,) in conn.execute("SELECT monthly_salary FROM users")}
    nets = {p["net"] for p in pay.values()}
    assert not ((salaries | nets) & {num_(r["cargo"]) for r in moves if "Nóminas" not in r["concepto"]})


def test_d10_reconciliation(files, conn):
    rows = sheet_rows(files["D10"], "Conciliacion")
    closing = parse_num(bank_rows(files)[-1]["saldo"])
    assert q2(find_row(rows, "Saldo según extracto bancario a 31/10/2026 (D09)")[1]) == closing
    adj = find_row(rows, "Saldo bancario ajustado")[1]
    book = find_row(rows, "Saldo según libros a 31/10/2026 (cuenta 572, D08)")[1]
    assert q2(adj) == q2(book) and q2(find_row(rows, "Diferencia no explicada")[1]) == 0
    assert q2(find_row(rows, "(+) Cobros contabilizados pendientes de abono en banco")[1]) == sql_one(conn, "SELECT amount FROM invoices WHERE invoice_number='FE-2026-0004'")
    may = sheet_rows(files["D08"], "Mayor")
    assert q2(find_row(may, "572")[5]) == q2(book)
    pend = [r for r in sheet_rows(files["D10"], "Partidas") if r[0] in ("Libros", "Banco")]
    assert len(pend) == 3


def test_d11_commission_settlement(files, conn):
    p = files["D11"]
    rows = sheet_rows(p, "Liquidacion octubre")
    for e in EMPS:
        r = find_row(rows, e)
        assert (r[2], q2(r[3]), q2(r[5]), q2(r[6])) == (sold_count(conn, OCT, e), sold_premium(conn, OCT, e), commission(conn, OCT, "ACCRUAL", e), commission(conn, OCT, "CLAWBACK", e))
        assert q2(r[7]) == q2(r[5] + r[6]) and q2(r[8]) == 0
    tot = find_row(rows, "Total")
    assert (tot[2], q2(tot[3]), q2(tot[5])) == (12, Decimal("9680.00"), Decimal("968.00"))
    hist = sheet_rows(p, "Historico")
    net = find_row(hist, "Total neto")
    for i, m in enumerate(MONTHS):
        assert q2(net[2 + i]) == commission(conn, m, "ACCRUAL") + commission(conn, m, "CLAWBACK")
    det = sheet_rows(p, "Detalle octubre")
    assert q2(find_row(det, "Total devengado", 4)[7]) == commission(conn, OCT, "ACCRUAL")


def test_d12_expense_register(files, conn):
    p = files["D12"]
    res = sheet_rows(p, "Resumen")
    tot = find_row(res, "Total gastos de explotación")
    for i, m in enumerate(MONTHS):
        assert q2(tot[1 + i]) == expenses(conn, m)
    assert q2(tot[2]) == Decimal("7250.00")
    es = {"RENT": "Alquiler", "SOFTWARE": "Software y licencias", "MARKETING": "Marketing", "PROFESSIONAL_SERVICES": "Servicios profesionales",
          "UTILITIES": "Suministros", "SUPPLIES": "Material y consumibles"}
    for cat, label in es.items():
        assert q2(find_row(res, label)[2]) == expenses(conn, OCT, cat)
    reg = sheet_rows(p, "Registro")
    assert sum(1 for r in reg if isinstance(r[0], str) and r[0].startswith("GAS-")) == sql_one(conn, "SELECT count(*) FROM expenses")
    assert q2(find_row(reg, "Total septiembre-diciembre", 5)[6]) == sql_one(conn, "SELECT sum(amount) FROM expenses")


def test_d13_accounting_close(files, conn, pay):
    t = pdf_lines(files["D13"])
    assert value(t, "Pólizas nuevas") == 12 and value(t, "Primas emitidas") == sold_premium(conn, OCT)
    assert value(t, "Comisiones de empleados") == commission(conn, OCT, "ACCRUAL") and value(t, "Gastos de explotación") == expenses(conn, OCT)
    rev, exp, gross, ssc, res = october_pnl(conn, pay, ss="by_person")
    assert value(t, "Resultado del mes") == res
    assert value(t, "Sueldos y salarios (agregado)") == -gross
    assert value(t, "Seguridad Social a cargo de la empresa") == -ssc      # suma de cuotas individuales (D08 agrega la base)
    assert value(t, "Saldo bancario a 31/10") == parse_num(bank_rows(files)[-1]["saldo"])
    assert any(eur(insurer_commission(conn, OCT)) in ln for ln in t)
    # A5: ningún sueldo individual
    full = "\n".join(t)
    for (s,) in conn.execute("SELECT monthly_salary FROM users"):
        assert eur(s) not in full


@pytest.mark.parametrize("code,emp", [(c, e) for c, (e, k) in PER_EMPLOYEE.items() if k == "activity"])
def test_activity_csv(files, conn, code, emp):
    rows = csv_rows(files[code])
    assert len(rows) == sold_count_all(conn, emp)
    assert {r["employee_code"] for r in rows} == {emp}
    oct_rows = [r for r in rows if r["sale_date"].startswith("2026-10")]
    assert len(oct_rows) == 3 and sum(Decimal(r["premium_eur"]) for r in oct_rows) == sold_premium(conn, OCT, emp)
    assert sum(Decimal(r["premium_eur"]) for r in rows) == sql_one(conn, "SELECT sum(p.premium) FROM policies p JOIN users u ON u.id=p.employee_id WHERE u.employee_code=%s", emp)
    assert sum(Decimal(r["commission_eur"]) for r in oct_rows) == commission(conn, OCT, "ACCRUAL", emp)
    assert sum(Decimal(r["clawback_eur"] or 0) for r in rows) == sql_one(conn, "SELECT coalesce(sum(c.amount),0) FROM commissions c JOIN users u ON u.id=c.employee_id WHERE c.kind='CLAWBACK' AND u.employee_code=%s", emp)
    assert (100 * sum(Decimal(r["premium_eur"]) for r in oct_rows) / target(conn, OCT, emp)).quantize(Decimal("0.1")) == attainment(conn, OCT, emp)


def sold_count_all(conn, emp):
    return sql_one(conn, "SELECT count(*) FROM policies p JOIN users u ON u.id=p.employee_id WHERE u.employee_code=%s", emp)


@pytest.mark.parametrize("code,emp", [(c, e) for c, (e, k) in PER_EMPLOYEE.items() if k == "payroll"])
def test_payslip_pdf(files, conn, pay, code, emp):
    t = pdf_lines(files[code])
    p = pay[emp]
    assert value(t, "Salario base mensual") == p["salary"] == sql_one(conn, "SELECT monthly_salary FROM users WHERE employee_code=%s", emp)
    assert value(t, "Comisiones devengadas (octubre)") == p["accrual"] == commission(conn, OCT, "ACCRUAL", emp)
    assert value(t, "Regularización por cancelaciones (clawback)") == p["clawback"]
    assert value(t, "Total devengado (bruto)") == p["gross"]
    assert value(t, "Seguridad Social del trabajador (6,48 %)") == p["ssw"]
    assert value(t, "Líquido a percibir") == p["net"]
    assert value(t, "Primas vendidas") == sold_premium(conn, OCT, emp) and value(t, "Cumplimiento") == attainment(conn, OCT, emp)


@pytest.mark.parametrize("code,emp", [(c, e) for c, (e, k) in PER_EMPLOYEE.items() if k == "contract"])
def test_contract_salary_from_users(files, conn, code, emp):
    t = " ".join(pdf_lines(files[code]))
    assert eur(sql_one(conn, "SELECT monthly_salary FROM users WHERE employee_code=%s", emp)) in t


def test_d02_budget_salaries_match_users(files, conn):
    rows = sheet_rows(files["D02"], "Personal")
    for code, salary in conn.execute("SELECT employee_code, monthly_salary FROM users"):
        assert q2(find_row(rows, code)[4]) == salary
