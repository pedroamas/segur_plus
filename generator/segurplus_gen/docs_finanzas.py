"""D07-D13: documentos de Finanzas (facturas, libro mayor, extracto bancario, conciliación, comisiones, gastos, cierre)."""
from __future__ import annotations

import csv
from collections import defaultdict
from datetime import date
from decimal import Decimal

from . import xlsxkit as X
from .config import ANCHOR_MONTH, MONTHS
from .dataset import EMPLOYEES
from .docs_direccion import pnl
from .facts import ACCOUNT_NAMES, CATEGORY_ES, MES, PRODUCT_ES, Facts, month_end, month_label
from .fmt import d, eur, num, pct
from .pdfkit import Report
from .spec import doc_spec

Z = Decimal("0")
O = ANCHOR_MONTH


def _hdr(ws, sp, title, extra=""):
    return X.title(ws, title, f"Documento {sp.code} · {sp.cls} · Importes en EUR sin IVA{extra}")


# --------------------------------------------------------------------------------------------------------------------
def build_d07(f: Facts, path) -> None:
    sp = doc_spec("D07")
    wb = X.new_book("Facturas emitidas y recibidas", "Finanzas")
    exp_by_inv = {e["invoice"]: e["number"] for e in f.expenses if e["invoice"]}
    ranges = {}
    for direction, name, party, ref_col in (("ISSUED", "Emitidas", "Aseguradora", "Nº factura"), ("RECEIVED", "Recibidas", "Proveedor", "Nº factura")):
        ws = wb.create_sheet(name)
        r0 = _hdr(ws, sp, f"Facturas {name.lower()}", " · estado a 15/01/2027")
        cols = [ref_col, party, "Periodo", "Fecha emisión", "Vencimiento", "Importe", "Estado", "Cuenta contable"] + (["Gasto asociado"] if direction == "RECEIVED" else [])
        X.header(ws, r0, cols)
        row = r0 + 1
        first = row
        for i in f.invoices_of(direction):
            vals = [i["number"], i["counterparty"], f"{i['month']:%Y-%m}", i["issue"], i["due"], i["amount"], i["status"], i["ledger"]]
            if direction == "RECEIVED":
                vals.append(exp_by_inv.get(i["number"], ""))
            X.row_values(ws, row, vals, {4: X.DATE, 5: X.DATE, 6: X.EUR})
            row += 1
        last = row - 1
        ws.cell(row=row, column=1, value="Total")
        ws.cell(row=row, column=6, value=f"=SUM(F{first}:F{last})").number_format = X.EUR
        X.total_style(ws, row, 1, len(cols))
        row += 2
        ws.cell(row=row, column=1, value="Subtotales por periodo").font = X.Font(bold=True)
        for m in MONTHS:
            row += 1
            ws.cell(row=row, column=1, value=f"Total {m:%Y-%m}")
            ws.cell(row=row, column=6, value=f'=SUMIF(C{first}:C{last},"{m:%Y-%m}",F{first}:F{last})').number_format = X.EUR
        row += 1
        ws.cell(row=row, column=1, value="Pendiente de cobro/pago (estado OPEN)")
        ws.cell(row=row, column=6, value=f'=SUMIF(G{first}:G{last},"OPEN",F{first}:F{last})').number_format = X.EUR
        X.total_style(ws, row, 1, len(cols))
        X.widths(ws, [16, 30, 10, 14, 14, 15, 9, 16, 16])
        ranges[name] = (first, last)
    rs = wb.create_sheet("Resumen", 0)
    r0 = _hdr(rs, sp, "Resumen de facturación por periodo")
    X.header(rs, r0, ["Periodo", "Emitidas (comisiones devengadas)", "Recibidas (proveedores)", "Diferencia"])
    row = r0 + 1
    fe, le = ranges["Emitidas"]
    fr, lr = ranges["Recibidas"]
    for m in MONTHS:
        rs.cell(row=row, column=1, value=f"{m:%Y-%m}")
        rs.cell(row=row, column=2, value=f'=SUMIF(Emitidas!C{fe}:C{le},A{row},Emitidas!F{fe}:F{le})').number_format = X.EUR
        rs.cell(row=row, column=3, value=f'=SUMIF(Recibidas!C{fr}:C{lr},A{row},Recibidas!F{fr}:F{lr})').number_format = X.EUR
        rs.cell(row=row, column=4, value=f"=B{row}-C{row}").number_format = X.EUR
        row += 1
    rs.cell(row=row, column=1, value="Total")
    for col, L in ((2, "B"), (3, "C"), (4, "D")):
        rs.cell(row=row, column=col, value=f"=SUM({L}{r0 + 1}:{L}{row - 1})").number_format = X.EUR
    X.total_style(rs, row, 1, 4)
    rs.cell(row=row + 2, column=1, value="Las facturas recibidas excluyen compras con tarjeta sin factura (ver registro de gastos, D12).").font = X.Font(italic=True)
    X.widths(rs, [14, 32, 26, 16])
    X.save(wb, path)


# --------------------------------------------------------------------------------------------------------------------
def build_d08(f: Facts, path) -> None:
    sp = doc_spec("D08")
    wb = X.new_book("Libro mayor octubre de 2026", "Finanzas")
    jr = f.journal(O)
    opening = f.balance_before(O)
    di = wb.create_sheet("Diario")
    r0 = _hdr(di, sp, "Libro diario — octubre de 2026", " · nóminas solo agregadas")
    X.header(di, r0, ["Asiento", "Fecha", "Cuenta", "Nombre de cuenta", "Concepto", "Debe", "Haber"])
    row = r0 + 1
    first = row
    for dt, acc, concept, debe, haber, n in jr:
        X.row_values(di, row, [n, dt, acc, ACCOUNT_NAMES[acc], concept, debe, haber], {2: X.DATE, 6: X.EUR, 7: X.EUR})
        row += 1
    last = row - 1
    di.cell(row=row, column=5, value="Totales")
    di.cell(row=row, column=6, value=f"=SUM(F{first}:F{last})").number_format = X.EUR
    di.cell(row=row, column=7, value=f"=SUM(G{first}:G{last})").number_format = X.EUR
    X.total_style(di, row, 1, 7)
    di.cell(row=row + 1, column=5, value="Descuadre (debe − haber)")
    di.cell(row=row + 1, column=6, value=f"=F{row}-G{row}").number_format = X.EUR
    X.widths(di, [9, 12, 9, 40, 62, 15, 15])

    ma = wb.create_sheet("Mayor", 0)
    r1 = _hdr(ma, sp, "Libro mayor — balance de sumas y saldos a 31/10/2026")
    X.header(ma, r1, ["Cuenta", "Nombre", "Saldo inicial (debe+/haber−)", "Debe octubre", "Haber octubre", "Saldo final"])
    accounts = sorted({a for _, a, *_ in jr} | set(opening))
    row = r1 + 1
    mfirst = row
    for acc in accounts:
        ma.cell(row=row, column=1, value=acc)
        ma.cell(row=row, column=2, value=ACCOUNT_NAMES[acc])
        ma.cell(row=row, column=3, value=float(opening.get(acc, Z))).number_format = X.EUR
        ma.cell(row=row, column=4, value=f'=SUMIF(Diario!C{first}:C{last},A{row},Diario!F{first}:F{last})').number_format = X.EUR
        ma.cell(row=row, column=5, value=f'=SUMIF(Diario!C{first}:C{last},A{row},Diario!G{first}:G{last})').number_format = X.EUR
        ma.cell(row=row, column=6, value=f"=C{row}+D{row}-E{row}").number_format = X.EUR
        row += 1
    mlast = row - 1
    ma.cell(row=row, column=1, value="Totales")
    for col in range(3, 7):
        L = "CDEF"[col - 3]
        ma.cell(row=row, column=col, value=f"=SUM({L}{mfirst}:{L}{mlast})").number_format = X.EUR
    X.total_style(ma, row, 1, 6)
    X.widths(ma, [10, 46, 24, 16, 16, 16])
    acc_row = {a: mfirst + i for i, a in enumerate(accounts)}

    pl = wb.create_sheet("Resultados")
    r2 = _hdr(pl, sp, "Cuenta de pérdidas y ganancias — octubre de 2026")
    X.header(pl, r2, ["Cuenta", "Concepto", "Importe"])
    row = r2 + 1
    pl.cell(row=row, column=1, value="705")
    pl.cell(row=row, column=2, value=ACCOUNT_NAMES["705"])
    pl.cell(row=row, column=3, value=f"=Mayor!E{acc_row['705']}-Mayor!D{acc_row['705']}").number_format = X.EUR
    rev = row
    row += 1
    exp_first = row
    for acc in [a for a in accounts if a[0] == "6"]:
        pl.cell(row=row, column=1, value=acc)
        pl.cell(row=row, column=2, value=ACCOUNT_NAMES[acc])
        pl.cell(row=row, column=3, value=f"=-(Mayor!D{acc_row[acc]}-Mayor!E{acc_row[acc]})").number_format = X.EUR
        row += 1
    pl.cell(row=row, column=2, value="Resultado del ejercicio (octubre)")
    pl.cell(row=row, column=3, value=f"=SUM(C{rev}:C{row - 1})").number_format = X.EUR
    X.total_style(pl, row, 1, 3)
    X.widths(pl, [10, 50, 18])
    X.save(wb, path)


# --------------------------------------------------------------------------------------------------------------------
def build_d09(f: Facts, path) -> None:
    opening, lines, closing = f.bank_lines(O)
    c = lambda x: num(x).replace(".", "")  # coma decimal, sin separador de miles (exportación bancaria)
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter=";", lineterminator="\n")
        w.writerow(["fecha_operacion", "fecha_valor", "concepto", "referencia", "cargo", "abono", "saldo"])
        w.writerow([d(O), d(O), "SALDO INICIAL", "", "", "", c(opening)])
        bal = opening
        for m in lines:
            bal += m.amount
            w.writerow([d(m.bank_date), d(m.bank_date), m.concept, m.ref, c(-m.amount) if m.amount < 0 else "", c(m.amount) if m.amount > 0 else "", c(bal)])
        w.writerow([d(month_end(O)), d(month_end(O)), "SALDO FINAL", "", "", "", c(closing)])


# --------------------------------------------------------------------------------------------------------------------
def _reconciliation_items(f: Facts):
    allm = f.cash_movements(date(2027, 1, 15))
    in_transit = [m for m in allm if m.kind == "RECEIPT" and m.book_date and m.book_date.replace(day=1) == O and m.bank_date.replace(day=1) != O]
    not_booked = [m for m in allm if m.book_date is None]
    return in_transit, not_booked


def build_d10(f: Facts, path) -> None:
    sp = doc_spec("D10")
    opening, bank_lines, closing = f.bank_lines(O)
    book_close = f.book_cash_balance(month_end(O))
    in_transit, not_booked = _reconciliation_items(f)
    wb = X.new_book("Conciliación bancaria octubre de 2026", "Finanzas")
    pt = wb.create_sheet("Partidas")
    r0 = _hdr(pt, sp, "Partidas pendientes de conciliar a 31/10/2026")
    X.header(pt, r0, ["Origen", "Fecha", "Referencia", "Concepto", "Importe", "Estado", "Acción propuesta"])
    row = r0 + 1
    item_rows = {}
    for m in in_transit:
        X.row_values(pt, row, ["Libros", m.book_date, m.ref, m.concept + " (abonado por el banco el " + d(m.bank_date) + ")", m.amount, "En tránsito", "Se concilia con el extracto de noviembre"],
                     {2: X.DATE, 5: X.EUR})
        item_rows["transit"] = row
        row += 1
    for m in not_booked:
        if m.kind == "UNIDENTIFIED":
            X.row_values(pt, row, ["Banco", m.bank_date, m.ref, m.concept, m.amount, "Sin identificar", "Solicitar justificante al banco; no contabilizar hasta identificar al ordenante"], {2: X.DATE, 5: X.EUR})
            item_rows["unidentified"] = row
        else:
            X.row_values(pt, row, ["Banco", m.bank_date, m.ref, m.concept, m.amount, "Pendiente de contabilizar", "Contabilizar en noviembre en la cuenta 626"], {2: X.DATE, 5: X.EUR})
            item_rows["fee"] = row
        row += 1
    X.widths(pt, [9, 12, 14, 62, 14, 24, 60])

    cs = wb.create_sheet("Conciliacion", 0)
    r1 = _hdr(cs, sp, "Conciliación bancaria — cuenta 572 a 31/10/2026")
    X.header(cs, r1, ["Concepto", "Importe"])
    cs["A5"], cs["B5"] = "Saldo según extracto bancario a 31/10/2026 (D09)", float(closing)
    cs["A6"], cs["B6"] = "(+) Cobros contabilizados pendientes de abono en banco", f"=Partidas!E{item_rows['transit']}"
    cs["A7"], cs["B7"] = "(−) Abonos bancarios no contabilizados (sin identificar)", f"=-Partidas!E{item_rows['unidentified']}"
    cs["A8"], cs["B8"] = "(+) Cargos bancarios no contabilizados", f"=-Partidas!E{item_rows['fee']}"
    cs["A9"], cs["B9"] = "Saldo bancario ajustado", "=SUM(B5:B8)"
    cs["A10"], cs["B10"] = "Saldo según libros a 31/10/2026 (cuenta 572, D08)", float(book_close)
    cs["A11"], cs["B11"] = "Diferencia no explicada", "=B9-B10"
    for rr in range(5, 12):
        cs[f"B{rr}"].number_format = X.EUR
    X.total_style(cs, 9, 1, 2)
    X.total_style(cs, 11, 1, 2)
    cs["A13"] = "Quedan 2 partidas sin conciliar (abono sin identificar y comisión bancaria); la partida en tránsito se concilia en noviembre."
    cs["A13"].font = X.Font(italic=True)
    X.widths(cs, [66, 18])

    mb = wb.create_sheet("Movimientos banco")
    r2 = _hdr(mb, sp, "Movimientos del extracto — octubre")
    X.header(mb, r2, ["Fecha", "Referencia", "Concepto", "Importe", "Conciliado"])
    row = r2 + 1
    bfirst = row
    for m in bank_lines:
        matched = m.book_date is not None and m.book_date.replace(day=1) == O
        X.row_values(mb, row, [m.bank_date, m.ref, m.concept, m.amount, "Sí" if matched else "No"], {1: X.DATE, 4: X.EUR})
        row += 1
    blast = row - 1
    mb.cell(row=row, column=3, value="Total movimientos")
    mb.cell(row=row, column=4, value=f"=SUM(D{bfirst}:D{blast})").number_format = X.EUR
    X.total_style(mb, row, 1, 5)
    mb.cell(row=row + 1, column=3, value="De los que sin conciliar")
    mb.cell(row=row + 1, column=4, value=f'=SUMIF(E{bfirst}:E{blast},"No",D{bfirst}:D{blast})').number_format = X.EUR
    X.widths(mb, [12, 16, 62, 15, 11])

    ml = wb.create_sheet("Movimientos libros")
    r3 = _hdr(ml, sp, "Movimientos de la cuenta 572 en libros — octubre")
    X.header(ml, r3, ["Fecha", "Referencia", "Concepto", "Importe", "Conciliado"])
    row = r3 + 1
    lfirst = row
    for m in f.cash_movements(date(2027, 1, 15)):
        if m.book_date and m.book_date.replace(day=1) == O:
            matched = m.bank_date is not None and m.bank_date.replace(day=1) == O
            X.row_values(ml, row, [m.book_date, m.ref, m.concept, m.amount, "Sí" if matched else "No"], {1: X.DATE, 4: X.EUR})
            row += 1
    llast = row - 1
    ml.cell(row=row, column=3, value="Total movimientos")
    ml.cell(row=row, column=4, value=f"=SUM(D{lfirst}:D{llast})").number_format = X.EUR
    X.total_style(ml, row, 1, 5)
    X.widths(ml, [12, 16, 62, 15, 11])
    X.save(wb, path)


# --------------------------------------------------------------------------------------------------------------------
def build_d11(f: Facts, path) -> None:
    sp = doc_spec("D11")
    wb = X.new_book("Liquidación de comisiones de empleados", "Finanzas")
    ws = wb.create_sheet("Liquidacion octubre")
    r0 = _hdr(ws, sp, "Liquidación de comisiones de empleados — octubre de 2026", " · comisión de empleado 10 % sobre prima")
    X.header(ws, r0, ["Empleado", "Nombre", "Pólizas", "Primas", "Tasa", "Comisión devengada", "Regularizaciones (clawback)", "Neto a liquidar", "Comprobación"])
    row = r0 + 1
    first = row
    for e in EMPLOYEES:
        X.row_values(ws, row, [e, f.employee_name(e), len(f.sold(O, e)), f.premium(O, e), 0.1, f.accrual(O, e), f.clawback(O, e)], {4: X.EUR, 5: "0%", 6: X.EUR, 7: X.EUR})
        ws.cell(row=row, column=8, value=f"=F{row}+G{row}").number_format = X.EUR
        ws.cell(row=row, column=9, value=f"=ROUND(D{row}*E{row},2)-F{row}").number_format = X.EUR
        row += 1
    last = row - 1
    ws.cell(row=row, column=1, value="Total")
    for col, L in ((3, "C"), (4, "D"), (6, "F"), (7, "G"), (8, "H"), (9, "I")):
        ws.cell(row=row, column=col, value=f"=SUM({L}{first}:{L}{last})").number_format = X.EUR if col != 3 else X.INT
    X.total_style(ws, row, 1, 9)
    X.widths(ws, [11, 24, 9, 14, 7, 18, 22, 16, 14])

    dt = wb.create_sheet("Detalle octubre")
    r1 = _hdr(dt, sp, "Detalle por póliza — octubre de 2026")
    X.header(dt, r1, ["Empleado", "Póliza", "Cliente", "Producto", "Tipo", "Base (prima)", "Tasa", "Importe"])
    pol = {p["number"]: p for p in f.policies}
    row = r1 + 1
    dfirst = row
    for c in f.commissions:
        if c["month"] == O:
            p = pol[c["policy"]]
            X.row_values(dt, row, [c["employee"], c["policy"], p["customer"], PRODUCT_ES[p["product"]], c["kind"], c["base"], float(c["rate"]), c["amount"]], {6: X.EUR, 7: "0%", 8: X.EUR})
            row += 1
    dlast = row - 1
    dt.cell(row=row, column=5, value="Total devengado")
    dt.cell(row=row, column=8, value=f'=SUMIF(E{dfirst}:E{dlast},"ACCRUAL",H{dfirst}:H{dlast})').number_format = X.EUR
    dt.cell(row=row + 1, column=5, value="Total clawback")
    dt.cell(row=row + 1, column=8, value=f'=SUMIF(E{dfirst}:E{dlast},"CLAWBACK",H{dfirst}:H{dlast})').number_format = X.EUR
    X.total_style(dt, row, 1, 8)
    X.widths(dt, [11, 16, 12, 10, 10, 14, 7, 14])

    hi = wb.create_sheet("Historico")
    r2 = _hdr(hi, sp, "Histórico de comisiones por mes (septiembre-diciembre)")
    X.header(hi, r2, ["Empleado", "Concepto"] + [MES[m.month].capitalize() for m in MONTHS] + ["Total"])
    row = r2 + 1
    for e in EMPLOYEES:
        for label, fn in (("Devengada", f.accrual), ("Clawback", f.clawback)):
            hi.cell(row=row, column=1, value=e)
            hi.cell(row=row, column=2, value=label)
            for i, m in enumerate(MONTHS):
                hi.cell(row=row, column=3 + i, value=float(fn(m, e))).number_format = X.EUR
            hi.cell(row=row, column=7, value=f"=SUM(C{row}:F{row})").number_format = X.EUR
            row += 1
    hi.cell(row=row, column=1, value="Total neto")
    for col in range(3, 8):
        L = "CDEFG"[col - 3]
        hi.cell(row=row, column=col, value=f"=SUM({L}{r2 + 1}:{L}{row - 1})").number_format = X.EUR
    X.total_style(hi, row, 1, 7)
    X.widths(hi, [11, 12, 14, 14, 14, 14, 15])
    X.save(wb, path)


# --------------------------------------------------------------------------------------------------------------------
def build_d12(f: Facts, path) -> None:
    sp = doc_spec("D12")
    wb = X.new_book("Registro de gastos", "Finanzas")
    ws = wb.create_sheet("Registro")
    r0 = _hdr(ws, sp, "Registro de gastos de explotación — septiembre a diciembre de 2026", " · excluye nóminas y comisiones")
    X.header(ws, r0, ["Nº gasto", "Fecha", "Periodo", "Categoría", "Proveedor", "Descripción", "Importe", "Cuenta", "Factura", "Estado", "Clave"])
    row = r0 + 1
    first = row
    for e in f.expenses:
        X.row_values(ws, row, [e["number"], e["date"], f"{e['date']:%Y-%m}", CATEGORY_ES[e["category"]], e["supplier"], e["description"], e["amount"],
                               e["ledger"], e["invoice"] or "sin factura (tarjeta)", "APPROVED", f"{e['date']:%Y-%m}|{CATEGORY_ES[e['category']]}"], {2: X.DATE, 7: X.EUR})
        row += 1
    last = row - 1
    ws.cell(row=row, column=6, value="Total septiembre-diciembre")
    ws.cell(row=row, column=7, value=f"=SUM(G{first}:G{last})").number_format = X.EUR
    X.total_style(ws, row, 1, 11)
    X.widths(ws, [16, 12, 9, 24, 28, 52, 14, 9, 22, 11, 36])
    rs = wb.create_sheet("Resumen", 0)
    r1 = _hdr(rs, sp, "Resumen de gastos por categoría y periodo")
    X.header(rs, r1, ["Categoría"] + [f"{m:%Y-%m}" for m in MONTHS] + ["Total"])
    row = r1 + 1
    cfirst = row
    for cat, label in CATEGORY_ES.items():
        rs.cell(row=row, column=1, value=label)
        for i, m in enumerate(MONTHS):
            rs.cell(row=row, column=2 + i, value=f'=SUMIF(Registro!K{first}:K{last},"{m:%Y-%m}|{label}",Registro!G{first}:G{last})').number_format = X.EUR
        rs.cell(row=row, column=6, value=f"=SUM(B{row}:E{row})").number_format = X.EUR
        row += 1
    rs.cell(row=row, column=1, value="Total gastos de explotación")
    for col in range(2, 7):
        L = "BCDEF"[col - 2]
        rs.cell(row=row, column=col, value=f"=SUM({L}{cfirst}:{L}{row - 1})").number_format = X.EUR
    X.total_style(rs, row, 1, 6)
    X.widths(rs, [30, 15, 15, 15, 15, 16])
    X.save(wb, path)


# --------------------------------------------------------------------------------------------------------------------
def build_d13(f: Facts, path) -> None:
    """Versión 1.0 (borrador) del informe de resultados de octubre; la v2.0 revisada por Dirección es D01.
    Cotización de empresa como suma de cuotas individuales (D08 la calcula sobre la base agregada): diferencia de redondeo declarada."""
    sp = doc_spec("D13")
    t = f.payroll_totals(O)
    pl = pnl(f, O, "ss_company_by_person")
    r = Report("Informe de cierre contable — octubre de 2026", "Cierre del mes y resumen de resultados (borrador 1.0)", code=sp.code,
               classification=sp.cls, version="1.0 (borrador)", date_str="06/11/2026", author="Responsable Financiero")
    r.kpis([("Pólizas nuevas", str(len(f.sold(O))), None, None), ("Primas emitidas", eur(f.premium(O)), None, None),
            ("Comisiones de empleados", eur(f.accrual(O)), None, None), ("Gastos de explotación", eur(f.expense_total(O)), None, None)])
    r.small("Este documento incluye el resumen de resultados en su versión 1.0 (borrador, 06/11/2026). Dirección emitió después la versión 2.0 "
            "(informe mensual de dirección, D01), que sustituye al resumen de este borrador.")
    r.h1("1. Resumen de resultados del mes (borrador 1.0)")
    r.p(f"Octubre cierra con {len(f.sold(O))} pólizas nuevas y {eur(f.premium(O))} de primas emitidas; el cumplimiento de objetivos es del {pct(f.attainment(O))} "
        f"sobre {eur(f.target(O))}. Las comisiones de empleados devengadas son {eur(f.accrual(O))} y los gastos de explotación suman {eur(f.expense_total(O))}. "
        "Previsión de altas de noviembre en este borrador: 40 pólizas (pendiente de validar con el Responsable Comercial).")
    r.table(["Empleado", "Pólizas", "Primas", "Objetivo", "Cumplim.", "Comisión 10 %"],
            [[f"{e} · {f.employee_name(e)}", str(len(f.sold(O, e))), eur(f.premium(O, e)), eur(f.target(O, e)), pct(f.attainment(O, e)), eur(f.accrual(O, e))] for e in EMPLOYEES]
            + [["Total equipo", str(len(f.sold(O))), eur(f.premium(O)), eur(f.target(O)), pct(f.attainment(O)), eur(f.accrual(O))]], [3.4, 1, 1.6, 1.6, 1.3, 1.6], total=True)
    r.h1("2. Cuenta de pérdidas y ganancias de octubre")
    by_acc: dict[str, Decimal] = defaultdict(Decimal)
    for e in f.expenses_of(O):
        by_acc[e["ledger"]] += e["amount"]
    rows = [["705", ACCOUNT_NAMES["705"], eur(pl["ingresos"])]]
    for acc in sorted(by_acc):
        rows.append([acc, ACCOUNT_NAMES[acc], eur(-by_acc[acc])])
    rows += [["640", ACCOUNT_NAMES["640"] + " (agregado)", eur(-pl["sueldos"])], ["642", ACCOUNT_NAMES["642"], eur(-pl["ss"])],
             ["", "Resultado del mes", eur(pl["resultado"])]]
    r.table(["Cuenta", "Concepto", "Importe"], rows, [1, 5, 2], total=True, align_right_from=2)
    r.p(f"Gastos de explotación de octubre (cuentas 62x): {eur(f.expense_total(O))}. Sueldos y salarios aparece solo como importe agregado "
        f"({eur(t['gross'])}, incluidas comisiones netas de regularizaciones por {eur(-f.clawback(O))}). Seguridad Social a cargo de la empresa calculada por empleado.")
    r.h1("3. Tesorería y conciliación")
    opening, _, closing = f.bank_lines(O)
    book = f.book_cash_balance(O.replace(day=31))
    r.table(["Concepto", "Importe"], [["Saldo bancario a 31/10", eur(closing)], ["Saldo en libros (cuenta 572) a 31/10", eur(book)],
                                      ["Partidas pendientes de conciliar", "3 (1 en tránsito, 2 sin conciliar)"]], [4, 3])
    r.p("Los detalles de la conciliación figuran en el documento de conciliación bancaria (D10). Hay un abono bancario sin identificar y una comisión "
        "bancaria pendientes de contabilizar.")
    r.h1("4. Devengos pendientes de facturar")
    r.p(f"A 31/10 quedan devengadas y pendientes de facturar a las aseguradoras comisiones de intermediación de octubre por {eur(f.insurer_commission(O))}; "
        "se facturan el 05/11/2026 con vencimiento a 15 días.")
    r.h1("5. Lista de verificación del cierre")
    r.table(["Tarea", "Estado"], [["Registro de facturas recibidas", "Completado"], ["Devengo de comisiones de aseguradoras", "Completado"],
                                  ["Contabilización de nóminas (agregada)", "Completado"], ["Conciliación bancaria", "Con partidas pendientes"],
                                  ["Revisión por Dirección", "Pendiente (versión 2.0, D01)"]], [4, 3], align_right_from=9)
    r.build(path)
