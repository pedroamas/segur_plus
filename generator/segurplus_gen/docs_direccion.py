"""D01-D06: documentos de Dirección (informe mensual, presupuesto, cuadro de mando, plan estratégico, actas, CRM)."""
from __future__ import annotations

import json
from collections import defaultdict
from datetime import date
from decimal import Decimal

from . import xlsxkit as X
from .budget import BUDGET_EMPLOYEE_RATE, BUDGET_INSURER_RATE, budget_rows
from .config import ANCHOR_MONTH, MONTHS
from .config import BUSINESS_DATE
from .dataset import EMPLOYEES
from .facts import CATEGORY_ES, MES, PRODUCT_ES, SS_COMPANY, Facts, month_label, next_month, prev_month
from .fmt import d, eur, num, pct, signed_pct
from .pdfkit import Report
from .spec import doc_spec

S, O, N, DEC = MONTHS
Z = Decimal("0")


def pnl(f: Facts, m: date, ss_key: str = "ss_company_aggregate") -> dict[str, Decimal]:
    t = f.payroll_totals(m)
    rev, exp = f.insurer_commission(m), f.expense_total(m)
    return {"ingresos": rev, "gastos": exp, "sueldos": t["gross"], "ss": t[ss_key], "resultado": rev - exp - t["gross"] - t[ss_key]}


def _oct_tables(r: Report, f: Facts, m: date = ANCHOR_MONTH) -> None:
    rows = []
    for e in EMPLOYEES:
        rows.append([f"{e} · {f.employee_name(e)}", str(len(f.sold(m, e))), eur(f.premium(m, e)), eur(f.target(m, e)),
                     pct(f.attainment(m, e)), eur(f.accrual(m, e))])
    rows.append(["Total equipo", str(len(f.sold(m))), eur(f.premium(m)), eur(f.target(m)), pct(f.attainment(m)), eur(f.accrual(m))])
    r.table(["Empleado", "Pólizas", "Primas", "Objetivo", "Cumplim.", "Comisión 10 %"], rows, [3.4, 1, 1.6, 1.6, 1.3, 1.6], total=True)


# --------------------------------------------------------------------------------------------------------------------
def build_d01(f: Facts, path) -> None:
    sp = doc_spec("D01")
    m, p = ANCHOR_MONTH, prev_month(ANCHOR_MONTH)
    r = Report("Informe mensual de dirección — octubre de 2026", "Resultados comerciales y económicos del mes",
               code=sp.code, classification=sp.cls, version="2.0 (final)", date_str="10/11/2026", author="Dirección General")
    r.kpis([("Pólizas nuevas", str(len(f.sold(m))), f"{signed_pct(len(f.sold(m)), len(f.sold(p)))} vs septiembre", False),
            ("Primas emitidas", eur(f.premium(m)), f"{signed_pct(f.premium(m), f.premium(p))} vs septiembre", False),
            ("Comisiones de empleados", eur(f.accrual(m)), None, None),
            ("Cumplimiento de objetivos", pct(f.attainment(m)), "objetivo 100 %", False)])
    r.h1("1. Resumen ejecutivo")
    r.p(f"Octubre cierra con {len(f.sold(m))} pólizas nuevas y {eur(f.premium(m))} de primas anuales emitidas, frente a "
        f"{len(f.sold(p))} pólizas y {eur(f.premium(p))} en septiembre. El equipo comercial alcanza el {pct(f.attainment(m))} del objetivo "
        f"de {eur(f.target(m))}: solo {EMPLOYEES[0]} supera su meta. La caída respecto a septiembre responde al cambio de CRM de la segunda "
        f"semana del mes y a la menor inversión en captación (marketing pasó de {eur(f.expense_by_category(p)['MARKETING'])} a "
        f"{eur(f.expense_by_category(m)['MARKETING'])}).")
    r.p("SegurPlus actúa como corredor: su ingreso es la comisión que paga la aseguradora por cada póliza intermediada y no asume riesgo "
        "técnico. Con la escala actual de cartera, esos ingresos no cubren todavía los gastos de estructura ni el personal; el resultado "
        "negativo es el esperable en una empresa en su segundo mes de actividad y está financiado con la aportación de socios.")
    r.h1("2. Actividad comercial")
    _oct_tables(r, f)
    pm = f.premium(m)
    r.table(["Producto", "Pólizas", "Primas", "% de primas"],
            [[PRODUCT_ES[pr], str(sum(1 for x in f.sold(m) if x["product"] == pr)), eur(sum((x["premium"] for x in f.sold(m) if x["product"] == pr), Z)),
              pct(f.pct(sum((x["premium"] for x in f.sold(m) if x["product"] == pr), Z), pm))] for pr in PRODUCT_ES]
            + [["Total", str(len(f.sold(m))), eur(pm), pct(Decimal("100"))]], [2, 1, 1.6, 1.4], total=True)
    canc = f.cancelled_in(m)
    r.p(f"Cancelaciones del mes: {len(canc)} pólizas canceladas, con regularización (clawback) de "
        f"comisión de empleado por {eur(-f.clawback(m))}. Las ventas se cuentan por fecha de venta, por lo que no alteran las cifras de octubre. "
        f"Cartera activa a 31/10: {f.active_portfolio(m)} pólizas.")
    r.h1("3. Resultado económico")
    a, b = pnl(f, p), pnl(f, m)
    rows = []
    for label, k in [("Ingresos por intermediación (comisiones de aseguradoras devengadas)", "ingresos"), ("Gastos de explotación (sin personal)", "gastos"),
                     ("Sueldos y salarios (agregado, incl. comisiones netas)", "sueldos"), ("Seguridad Social a cargo de la empresa", "ss"), ("Resultado del mes", "resultado")]:
        rows.append([label, eur(a[k]), eur(b[k]), signed_pct(b[k], a[k]) if a[k] > 0 and b[k] > 0 else "—"])
    r.table(["Concepto", "Septiembre", "Octubre", "Variación"], rows, [4.2, 1.5, 1.5, 1.2], total=True)
    r.p(f"Los gastos de explotación de octubre suman {eur(f.expense_total(m))} (sin IVA, sin nóminas ni comisiones). Distribución por categoría:")
    cat = f.expense_by_category(m)
    r.table(["Categoría", "Importe", "% del total"], [[CATEGORY_ES[c], eur(v), pct(f.pct(v, f.expense_total(m)))] for c, v in cat.items()]
            + [["Total gastos de explotación", eur(f.expense_total(m)), pct(Decimal("100"))]], [3, 1.6, 1.2], total=True)
    r.h1("4. Conclusiones y acciones")
    r.bullets([f"Recuperar el ritmo de altas en noviembre: objetivo aprobado de {eur(f.target(next_month(m)))} en primas (campaña de Black Friday).",
               f"Plan de seguimiento para los comerciales por debajo del 75 % del objetivo ({', '.join(e for e in EMPLOYEES if f.attainment(m, e) < 75)}).",
               "Mantener el gasto de marketing dentro del presupuesto y medir el coste de captación por póliza.",
               "Previsión de altas: 55 en noviembre y 70 en diciembre (estacionalidad de fin de año).",
               "Revisar con Finanzas el calendario de cobro a aseguradoras para mejorar la tesorería."])
    r.small("Versión 2.0 (10/11/2026): sustituye al borrador 1.0 elaborado por Finanzas el 06/11/2026 (resumen incluido en el informe de cierre contable, D13). "
            "Cambios: previsión de altas actualizada, plan de seguimiento comercial y conclusiones de Dirección.")
    r.build(path)


# --------------------------------------------------------------------------------------------------------------------
def build_d02(f: Facts, path) -> None:
    sp = doc_spec("D02")
    bud = budget_rows(f)
    wb = X.new_book("Presupuesto anual 2026", "Dirección General")
    hy = wb.create_sheet("Hipotesis")
    nxt = X.title(hy, "Hipótesis del presupuesto 2026", f"Documento {sp.code} · {sp.cls} · Ejercicio de arranque: operación desde el 01/09/2026")
    X.header(hy, nxt, ["Parámetro", "Valor", "Nota"])
    hy["A5"], hy["B5"], hy["C5"] = "Comisión media de las aseguradoras", float(BUDGET_INSURER_RATE), "sobre primas anuales emitidas (corredor, A1)"
    hy["A6"], hy["B6"], hy["C6"] = "Comisión de empleados", float(BUDGET_EMPLOYEE_RATE), "sobre prima de cada póliza vendida"
    hy["A7"], hy["B7"], hy["C7"] = "Seguridad Social a cargo de la empresa", float(SS_COMPANY), "sobre sueldo bruto + comisiones"
    hy["A8"], hy["B8"], hy["C8"] = "Primas presupuestadas", "objetivos de ventas", "suma de los objetivos mensuales por comercial"
    for rr in (5, 6, 7):
        hy[f"B{rr}"].number_format = "0.00%"
    X.widths(hy, [42, 22, 55])

    pe = wb.create_sheet("Personal")
    nxt = X.title(pe, "Plantilla y salarios (RESTRICTED: solo Dirección)", "Salario bruto mensual en 12 pagas (pagas extra prorrateadas)")
    X.header(pe, nxt, ["Código", "Nombre", "Departamento", "Rol", "Salario mensual"] + [MES[m.month].capitalize() for m in MONTHS] + ["Total 2026"])
    row = nxt + 1
    first = row
    for code, u in f.users.items():
        X.row_values(pe, row, [code, u.full_name, u.department, u.role, u.salary], {5: X.EUR})
        for i in range(4):
            pe.cell(row=row, column=6 + i, value=f"=$E{row}").number_format = X.EUR
        pe.cell(row=row, column=10, value=f"=SUM(F{row}:I{row})").number_format = X.EUR
        row += 1
    last = row - 1
    pe.cell(row=row, column=1, value="Total plantilla")
    for col in range(5, 11):
        L = "EFGHIJ"[col - 5]
        pe.cell(row=row, column=col, value=f"=SUM({L}{first}:{L}{last})").number_format = X.EUR
    X.total_style(pe, row, 1, 10)
    pe.cell(row=row + 1, column=1, value="Seguridad Social empresa (sobre sueldos)")
    for col in range(6, 11):
        L = "FGHIJ"[col - 6]
        pe.cell(row=row + 1, column=col, value=f"=ROUND({L}{row}*Hipotesis!$B$7,2)").number_format = X.EUR
    X.widths(pe, [10, 24, 15, 15, 16, 13, 13, 13, 13, 15])
    tot_row = row

    rs = wb.create_sheet("Resumen", 0)
    nxt = X.title(rs, "Presupuesto anual 2026 (incluye salarios)", f"Documento {sp.code} · {sp.cls} · Importes en EUR sin IVA · Ejercicio de arranque septiembre-diciembre")
    X.header(rs, nxt, ["Concepto"] + [MES[m.month].capitalize() for m in MONTHS] + ["Total 2026"])
    r0 = nxt + 1
    rs.cell(row=r0, column=1, value="Primas anuales presupuestadas (memo)")
    for i, m in enumerate(MONTHS):
        rs.cell(row=r0, column=2 + i, value=float(bud[m]["premium"])).number_format = X.EUR
    rs.cell(row=r0, column=6, value=f"=SUM(B{r0}:E{r0})").number_format = X.EUR
    rs.cell(row=r0 + 1, column=1, value="Ingresos por intermediación (comisiones de aseguradoras)")
    for i in range(4):
        L = "BCDE"[i]
        rs.cell(row=r0 + 1, column=2 + i, value=f"=ROUND({L}{r0}*Hipotesis!$B$5,2)").number_format = X.EUR
    rs.cell(row=r0 + 1, column=6, value=f"=SUM(B{r0 + 1}:E{r0 + 1})").number_format = X.EUR
    X.total_style(rs, r0 + 1, 1, 6)
    rr = r0 + 3
    rs.cell(row=rr - 1, column=1, value="Gastos de explotación").font = X.Font(bold=True)
    g0 = rr
    from .budget import EXPENSE_BUDGET
    for c, vals in EXPENSE_BUDGET.items():
        rs.cell(row=rr, column=1, value=CATEGORY_ES[c])
        for i, m in enumerate(MONTHS):
            rs.cell(row=rr, column=2 + i, value=float(Decimal(vals[m]))).number_format = X.EUR
        rs.cell(row=rr, column=6, value=f"=SUM(B{rr}:E{rr})").number_format = X.EUR
        rr += 1
    rs.cell(row=rr, column=1, value="Total gastos de explotación")
    for col in range(2, 7):
        L = "BCDEF"[col - 2]
        rs.cell(row=rr, column=col, value=f"=SUM({L}{g0}:{L}{rr - 1})").number_format = X.EUR
    X.total_style(rs, rr, 1, 6)
    gt = rr
    rr += 2
    rs.cell(row=rr - 1, column=1, value="Personal (agregado)").font = X.Font(bold=True)
    p0 = rr
    rs.cell(row=rr, column=1, value="Sueldos y salarios fijos (hoja Personal)")
    for i in range(4):
        rs.cell(row=rr, column=2 + i, value=f"=Personal!{'FGHI'[i]}{tot_row}").number_format = X.EUR
    rs.cell(row=rr, column=6, value=f"=SUM(B{rr}:E{rr})").number_format = X.EUR
    rs.cell(row=rr + 1, column=1, value="Comisiones de empleados")
    for i in range(4):
        rs.cell(row=rr + 1, column=2 + i, value=f"=ROUND({'BCDE'[i]}{r0}*Hipotesis!$B$6,2)").number_format = X.EUR
    rs.cell(row=rr + 1, column=6, value=f"=SUM(B{rr + 1}:E{rr + 1})").number_format = X.EUR
    rs.cell(row=rr + 2, column=1, value="Seguridad Social a cargo de la empresa")
    for i in range(4):
        L = "BCDE"[i]
        rs.cell(row=rr + 2, column=2 + i, value=f"=ROUND(({L}{rr}+{L}{rr + 1})*Hipotesis!$B$7,2)").number_format = X.EUR
    rs.cell(row=rr + 2, column=6, value=f"=SUM(B{rr + 2}:E{rr + 2})").number_format = X.EUR
    rs.cell(row=rr + 3, column=1, value="Total personal")
    for col in range(2, 7):
        L = "BCDEF"[col - 2]
        rs.cell(row=rr + 3, column=col, value=f"=SUM({L}{rr}:{L}{rr + 2})").number_format = X.EUR
    X.total_style(rs, rr + 3, 1, 6)
    pt = rr + 3
    rr += 5
    rs.cell(row=rr, column=1, value="Resultado presupuestado")
    for col in range(2, 7):
        L = "BCDEF"[col - 2]
        rs.cell(row=rr, column=col, value=f"={L}{r0 + 1}-{L}{gt}-{L}{pt}").number_format = X.EUR
    X.total_style(rs, rr, 1, 6)
    rs.cell(row=rr + 2, column=1, value="Nota: ejercicio de arranque. Se presupuesta una pérdida inicial financiada con la aportación de socios.").font = X.Font(italic=True)
    X.widths(rs, [58, 15, 15, 15, 15, 17])
    X.save(wb, path)


# --------------------------------------------------------------------------------------------------------------------
def build_d03(f: Facts, path) -> None:
    """Cuadro de mando en JSON (alimenta un BI). Comparte con D02 las líneas de ingresos y gastos presupuestados, sin sueldos individuales."""
    sp = doc_spec("D03")
    bud = budget_rows(f)
    months = {}
    for m in MONTHS:
        t = f.payroll_totals(m)
        pl = pnl(f, m)
        months[f"{m:%Y-%m}"] = {
            "polizas_nuevas": len(f.sold(m)), "primas_eur": str(f.premium(m)), "objetivo_primas_eur": str(f.target(m)),
            "cumplimiento_pct": str(f.attainment(m)), "comisiones_empleados_devengadas_eur": str(f.accrual(m)),
            "regularizaciones_clawback_eur": str(f.clawback(m)), "ingresos_intermediacion_eur": str(f.insurer_commission(m)),
            "gastos_explotacion_eur": str(f.expense_total(m)), "sueldos_y_salarios_agregado_eur": str(t["gross"]),
            "seguridad_social_empresa_eur": str(t["ss_company_aggregate"]), "resultado_eur": str(pl["resultado"]),
            "cartera_activa_fin_de_mes": f.active_portfolio(m),
            "cancelaciones_del_mes": len(f.cancelled_in(m)),
        }
    out = {
        "meta": {"documento": sp.code, "titulo": "Cuadro de mando KPI", "clasificacion": sp.cls, "generado": BUSINESS_DATE.isoformat(),
                 "moneda": "EUR", "periodo": "2026-09/2026-12", "mes_ancla": "2026-10", "fuente": "PostgreSQL (SegurPlus)"},
        "kpis_mensuales": months,
        "octubre_por_empleado": [
            {"empleado": e, "nombre": f.employee_name(e), "polizas": len(f.sold(ANCHOR_MONTH, e)), "primas_eur": str(f.premium(ANCHOR_MONTH, e)),
             "objetivo_eur": str(f.target(ANCHOR_MONTH, e)), "cumplimiento_pct": str(f.attainment(ANCHOR_MONTH, e)),
             "comision_devengada_eur": str(f.accrual(ANCHOR_MONTH, e))} for e in EMPLOYEES],
        "presupuesto_2026": {
            "hipotesis": {"comision_media_aseguradoras": str(BUDGET_INSURER_RATE), "comision_empleados": str(BUDGET_EMPLOYEE_RATE),
                          "seguridad_social_empresa": str(SS_COMPANY)},
            "meses": {f"{m:%Y-%m}": {"primas_presupuestadas_eur": str(bud[m]["premium"]), "ingresos_intermediacion_eur": str(bud[m]["revenue"]),
                                    "gastos_explotacion_por_categoria_eur": {c: str(v) for c, v in bud[m]["expenses"].items()},
                                    "personal_agregado_eur": str(bud[m]["salaries"] + bud[m]["commissions"] + bud[m]["ss"])} for m in MONTHS}},
        "umbrales": {"cumplimiento_objetivo_verde_pct": 100, "cumplimiento_objetivo_ambar_pct": 75},
    }
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2, sort_keys=False)
        fh.write("\n")


# --------------------------------------------------------------------------------------------------------------------
def build_d04(f: Facts, path) -> None:
    sp = doc_spec("D04")
    r = Report("Plan estratégico 2026-2028", "Hoja de ruta de SegurPlus, corredor de seguros online", code=sp.code, classification=sp.cls,
               version="1.1", date_str="02/12/2026", author="Dirección General")
    base_n, base_p = len(f.policies), f.premium()
    r.h1("1. Visión y posicionamiento")
    r.p("SegurPlus aspira a ser el corredor online de referencia para particulares y pequeñas empresas en auto, hogar, vida y salud: "
        "asesoramiento independiente entre varias aseguradoras, contratación sin papeles y atención de un equipo comercial propio. "
        "Como mediador no asumimos riesgo de suscripción; nuestro negocio es la calidad del asesoramiento, la retención de la cartera y "
        "la eficiencia de la venta.")
    r.h1("2. Punto de partida (septiembre-diciembre de 2026)")
    r.kpis([("Altas desde el lanzamiento", str(base_n), None, None), ("Primas anuales intermediadas", eur(base_p), None, None),
            ("Cartera activa a 31/12", str(f.active_portfolio(DEC)), None, None), ("Aportación de socios", "200.000,00 €", None, None)])
    r.p("El lanzamiento comercial se produjo el 1 de septiembre de 2026 sin cartera previa. Octubre fue un mes de transición (cambio de CRM) y "
        "noviembre y diciembre confirmaron la sensibilidad de la demanda a las campañas de captación. La estructura de costes actual, "
        "con el equipo completo contratado, exige multiplicar el volumen de primas para alcanzar el equilibrio.")
    r.h1("3. Objetivos estratégicos")
    r.table(["Palanca", "2026 (real, sep-dic)", "2027 (objetivo)", "2028 (objetivo)"],
            [["Altas anuales de pólizas", str(base_n), "1.100", "2.400"], ["Primas anuales intermediadas", eur(base_p), eur(Decimal("900000")), eur(Decimal("2200000"))],
             ["Ingresos por comisiones", eur(f.insurer_commission()), eur(Decimal("140000")), eur(Decimal("340000"))],
             ["Plantilla (personas)", "7", "11", "16"], ["Resultado de explotación", "pérdida de arranque", "-60.000 € a -20.000 €", "primer ejercicio positivo"]],
            [3, 2, 2, 2])
    r.bullets(["Alcanzar el punto de equilibrio operativo en el cuarto trimestre de 2028.",
               "Superar el 85 % de retención de pólizas a la primera renovación (las primeras renovaciones vencen a partir de septiembre de 2027).",
               "Diversificar: pasar de 5 a 8 aseguradoras colaboradoras y lanzar la línea de pymes (RC y comercio) en 2028.",
               "Reducir el coste de captación por póliza un 30 % mediante venta cruzada y recomendación."])
    r.h1("4. Hoja de ruta")
    r.table(["Fase", "Periodo", "Hitos", "Responsable"],
            [["Consolidación", "T1-T2 2027", "Estabilizar CRM, playbook comercial, métricas semanales", "Responsable Comercial"],
             ["Crecimiento", "T3-T4 2027", "Campañas estacionales, 2 aseguradoras nuevas, primera renovación", "Director General"],
             ["Escala", "2028", "Línea de pymes, 5 nuevas contrataciones, automatización de la emisión", "Dirección"]], [1.4, 1.2, 4, 1.8], align_right_from=9)
    r.h1("5. Financiación y reservado")
    r.p("El plan se financia con los 200.000 € aportados por los socios y una segunda ampliación de hasta 250.000 € condicionada a alcanzar "
        "1.000 pólizas en cartera a finales de 2027. Se ha explorado, sin compromiso, la adquisición de una pequeña correduría regional de "
        "hogar y auto con unas 900 pólizas en cartera; la decisión se aplaza al segundo trimestre de 2027. Este apartado es reservado a Dirección.")
    r.h1("6. Riesgos principales")
    r.table(["Riesgo", "Probabilidad", "Impacto", "Mitigación"],
            [["Dependencia de pocas aseguradoras", "Media", "Alto", "Convenios con 8 aseguradoras en 2028"],
             ["Baja retención en renovaciones", "Media", "Alto", "Seguimiento proactivo 60 días antes"],
             ["Coste de captación creciente", "Alta", "Medio", "Referidos y venta cruzada"],
             ["Incidente de seguridad de datos", "Baja", "Muy alto", "Política de seguridad y permisos mínimos"]], [3, 1.2, 1.2, 3.4], align_right_from=9)
    r.build(path)


# --------------------------------------------------------------------------------------------------------------------
def build_d05(f: Facts, path) -> None:
    sp = doc_spec("D05")
    r = Report("Actas del comité de dirección", "Septiembre a diciembre de 2026 · Reservado", code=sp.code, classification=sp.cls,
               version="1.0", date_str="10/12/2026", author="Secretaría de Dirección")
    att = "Asistentes: Director General (DIR001), Responsable Financiero (ACC001), Responsable Comercial (COM001)."
    sessions = [
        (date(2026, 10, 6), S, "Revisión de septiembre y ajuste de objetivos",
         [f"Se presentan los resultados del primer mes: {len(f.sold(S))} pólizas y {eur(f.premium(S))} de primas, cumplimiento del {pct(f.attainment(S))}.",
          "Se aprueba mantener la inversión en captación hasta fin de año.",
          "Se acuerda revisar la plataforma CRM por problemas de sincronización con la web de contratación."],
         [("Migración del CRM antes del 20/10", "Responsable Comercial", "20/10/2026"), ("Informe de tesorería a 30 días", "Responsable Financiero", "15/10/2026")]),
        (date(2026, 11, 10), O, "Revisión de octubre y plan de seguimiento comercial",
         [f"Octubre: {len(f.sold(O))} pólizas, {eur(f.premium(O))} de primas y cumplimiento del {pct(f.attainment(O))}; resultado negativo conforme al presupuesto de arranque.",
          f"Se detecta rendimiento por debajo del 70 % del objetivo en {', '.join(f'{e}' for e in EMPLOYEES if f.attainment(O, e) < 70)}: "
          "se acuerda plan de acompañamiento de 8 semanas con revisión semanal, sin medidas disciplinarias.",
          "Revisión salarial: se descarta cualquier subida en 2026; se estudiará una mejora del variable para 2027 sobre la base de retención de cartera.",
          "Se aprueba la campaña de Black Friday con un presupuesto adicional de marketing."],
         [("Plan de acompañamiento individual", "Responsable Comercial", "17/11/2026"), ("Presupuesto de campaña de Black Friday", "Responsable Financiero", "13/11/2026")]),
        (date(2026, 12, 9), N, "Revisión de noviembre y cierre del ejercicio",
         [f"Noviembre: {len(f.sold(N))} pólizas, {eur(f.premium(N))} de primas y cumplimiento del {pct(f.attainment(N))}; la campaña mejora la captación.",
          "Se aprueba el calendario de cierre contable y la presentación del presupuesto 2027 en enero.",
          "Se informa de una posible operación de adquisición de una correduría regional; se acuerda tratarla solo en comité reservado."],
         [("Presupuesto 2027", "Director General", "15/01/2027"), ("Cierre contable de diciembre", "Responsable Financiero", "12/01/2027")]),
    ]
    for dt, m, title, points, actions in sessions:
        r.h1(f"Acta del {d(dt)} — {title}")
        r.small(att)
        r.h2("Puntos tratados")
        r.bullets(points)
        r.h2("Acuerdos y acciones")
        r.table(["Acción", "Responsable", "Fecha límite"], [list(a) for a in actions], [3.5, 2, 1.3], align_right_from=9)
    r.build(path)


# --------------------------------------------------------------------------------------------------------------------
def build_d06(f: Facts, path) -> None:
    sp = doc_spec("D06")
    m = ANCHOR_MONTH
    r = Report("Rendimiento comercial (CRM)", "Septiembre a diciembre de 2026 · Mes ancla: octubre", code=sp.code, classification=sp.cls,
               version="1.0", date_str="15/01/2027", author="Responsable Comercial")
    r.kpis([("Pólizas nuevas (octubre)", str(len(f.sold(m))), None, None), ("Primas (octubre)", eur(f.premium(m)), None, None),
            ("Cumplimiento (octubre)", pct(f.attainment(m)), None, False), ("Pólizas totales sep-dic", str(len(f.policies)), None, None)])
    r.h1("1. Octubre de 2026 por comercial")
    _oct_tables(r, f)
    r.h1("2. Evolución mensual")
    rows = []
    for mm in MONTHS:
        rows.append([month_label(mm).capitalize(), str(len(f.sold(mm))), eur(f.premium(mm)), eur(f.target(mm)), pct(f.attainment(mm)),
                     str(len(f.cancelled_in(mm)))])
    rows.append(["Total sep-dic", str(len(f.policies)), eur(f.premium()), eur(sum((f.target(x) for x in MONTHS), Z)),
                 pct(f.pct(f.premium(), sum((f.target(x) for x in MONTHS), Z))), str(sum(len(f.cancelled_in(x)) for x in MONTHS))])
    r.table(["Mes", "Pólizas", "Primas", "Objetivo", "Cumplim.", "Cancelaciones"], rows, [2.4, 1, 1.6, 1.6, 1.2, 1.4], total=True)
    r.bar_chart([MES[x.month][:3].capitalize() for x in MONTHS], {"Primas": [f.premium(x) for x in MONTHS], "Objetivo": [f.target(x) for x in MONTHS]},
                "Primas frente a objetivo (EUR)")
    r.h1("3. Cumplimiento por comercial y mes")
    r.table(["Comercial"] + [MES[x.month][:3].capitalize() for x in MONTHS],
            [[f"{e} · {f.employee_name(e)}"] + [pct(f.attainment(x, e)) for x in MONTHS] for e in EMPLOYEES], [3.2, 1, 1, 1, 1])
    r.h1("4. Mezcla de producto y aseguradoras (sep-dic)")
    tot = f.premium()
    r.table(["Producto", "Pólizas", "Primas", "% primas"], [[PRODUCT_ES[p_], str(sum(1 for x in f.policies if x["product"] == p_)),
            eur(sum((x["premium"] for x in f.policies if x["product"] == p_), Z)), pct(f.pct(sum((x["premium"] for x in f.policies if x["product"] == p_), Z), tot))]
            for p_ in PRODUCT_ES], [2, 1, 1.6, 1.2])
    by_ins: dict[str, list] = defaultdict(lambda: [0, Z, Z])
    for x in f.policies:
        by_ins[x["insurer"]][0] += 1
        by_ins[x["insurer"]][1] += x["premium"]
        by_ins[x["insurer"]][2] += x["commission"]
    r.table(["Aseguradora", "Pólizas", "Primas", "Comisión devengada"], [[k, str(v[0]), eur(v[1]), eur(v[2])] for k, v in sorted(by_ins.items())], [2.6, 1, 1.6, 1.8])
    r.h1("5. Renovaciones previstas en 2027")
    ren: dict[str, list] = defaultdict(lambda: [0, Z])
    for x in f.policies:
        if x["status"] == "ACTIVE":
            ren[f"{x['renewal_date']:%Y-%m}"][0] += 1
            ren[f"{x['renewal_date']:%Y-%m}"][1] += x["premium"]
    r.table(["Mes de renovación", "Pólizas", "Prima anual"], [[k, str(v[0]), eur(v[1])] for k, v in sorted(ren.items())], [2, 1, 1.6])
    r.p("Las cancelaciones y renovaciones afectan solo a pólizas vendidas desde el 1 de septiembre de 2026 (no existe cartera previa).")
    r.build(path)
