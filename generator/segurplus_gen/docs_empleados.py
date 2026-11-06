"""D14-D25: contratos, actividad comercial (CSV tipo CRM) y nóminas con comisiones de cada empleado, generados desde `users` y la BD."""
from __future__ import annotations

import csv
from decimal import Decimal

from .catalog import PER_EMPLOYEE
from .config import ANCHOR_MONTH, MONTHS
from .facts import MES, PRODUCT_ES, Facts, last_workday, month_end
from .fmt import d, eur, num, pct
from .pdfkit import Report
from .spec import doc_spec

Z = Decimal("0")
O = ANCHOR_MONTH


def build_contract(f: Facts, code: str, path) -> None:
    sp = doc_spec(code)
    emp, _ = PER_EMPLOYEE[code]
    u = f.users[emp]
    r = Report("Contrato de trabajo indefinido", f"{u.full_name} · {emp}", code=code, classification=sp.cls, version="1.0",
               date_str="Firmado el 28/08/2026", author="SegurPlus Mediación de Seguros, S.L.")
    r.p("En Madrid, a 28 de agosto de 2026, REUNIDOS: de una parte, SegurPlus Mediación de Seguros, S.L. (sociedad ficticia, correduría de seguros online), "
        "representada por el Director General (DIR001), en adelante la Empresa; y de otra, " + u.full_name +
        f" (código de empleado {emp}), en adelante la Persona Trabajadora. Ambas partes se reconocen capacidad legal suficiente y acuerdan las siguientes cláusulas.")
    clauses = [
        ("Primera. Objeto y categoría", f"La Persona Trabajadora presta servicios como asesor comercial de seguros en el departamento {u.department.capitalize()}, "
         "encargado de asesorar y contratar pólizas de auto, hogar, vida y salud con las aseguradoras colaboradoras de la Empresa. La relación es indefinida y a jornada completa, con inicio el "
         + d(u.hire_date) + "."),
        ("Segunda. Periodo de prueba", "Se pacta un periodo de prueba de seis meses desde la fecha de inicio, durante el cual cualquiera de las partes podrá resolver el contrato sin preaviso ni indemnización."),
        ("Tercera. Jornada y vacaciones", "La jornada ordinaria es de 40 horas semanales en horario flexible de oficina o remoto. Se disfrutan 22 días laborables de vacaciones por año natural, prorrateados en 2026 "
         "según el procedimiento de gastos y vacaciones vigente (G03)."),
        ("Cuarta. Retribución fija", f"La retribución bruta fija es de {eur(u.salary)} mensuales en doce pagas (pagas extraordinarias prorrateadas), pagaderas el último día hábil de cada mes."),
        ("Quinta. Retribución variable", "Se devengará una comisión del 10 % de la prima anual de cada póliza vendida, en el mes de su venta. Si la póliza se cancela, "
         "se regularizará la comisión (clawback) por el mismo importe en el mes de la cancelación. Cada mes se fija un objetivo de primas por el Responsable Comercial."),
        ("Sexta. Confidencialidad y protección de datos", "La Persona Trabajadora guardará secreto sobre la información de clientes, económica y estratégica de la Empresa durante y tras la relación laboral, "
         "y tratará los datos personales conforme a la normativa de protección de datos y a la política de seguridad de la información (G02). El acceso a documentos se limita a su rol y a sus propios documentos."),
        ("Séptima. Medios y uso de sistemas", "La Empresa facilitará los medios necesarios. El uso de sistemas, correo y herramientas de inteligencia artificial se ajustará al código de conducta (G01)."),
        ("Octava. Extinción", "El contrato se extinguirá por las causas legales, con preaviso de quince días en caso de baja voluntaria."),
        ("Novena. Normativa aplicable", "En lo no previsto, regirán el Estatuto de los Trabajadores y el convenio colectivo del sector aplicable (referencia ficticia para este ejercicio)."),
    ]
    for h, text in clauses:
        r.h2(h)
        r.p(text)
    r.spacer(10)
    r.table(["Por la Empresa", "La Persona Trabajadora"], [["Director General (DIR001)", u.full_name + f" ({emp})"], ["Firmado electrónicamente", "Firmado electrónicamente"]],
            [1, 1], zebra=False, align_right_from=9)
    r.build(path)


def build_activity(f: Facts, code: str, path) -> None:
    emp, _ = PER_EMPLOYEE[code]
    claw = {c["policy"]: c["amount"] for c in f.commissions if c["kind"] == "CLAWBACK"}
    acc = {c["policy"]: c["amount"] for c in f.commissions if c["kind"] == "ACCRUAL"}
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["employee_code", "policy_number", "customer_number", "customer_name", "product", "insurer", "premium_eur", "sale_date",
                    "start_date", "renewal_date", "status", "cancelled_at", "commission_eur", "clawback_eur"])
        for p in f.sold(emp=emp):
            w.writerow([emp, p["number"], p["customer"], p["customer_name"], p["product"], p["insurer"], p["premium"], p["sale_date"].isoformat(),
                        p["start_date"].isoformat(), p["renewal_date"].isoformat(), p["status"], p["cancelled_at"].isoformat() if p["cancelled_at"] else "",
                        acc[p["number"]], claw.get(p["number"], "")])


def build_payroll(f: Facts, code: str, path) -> None:
    sp = doc_spec(code)
    emp, _ = PER_EMPLOYEE[code]
    row = next(x for x in f.payroll(O) if x.code == emp)
    u = f.users[emp]
    r = Report("Nómina y comisiones — octubre de 2026", f"{u.full_name} · {emp}", code=code, classification=sp.cls, version="1.0",
               date_str=f"Periodo 01/10/2026 - 31/10/2026 · Pago {d(last_workday(O))}", author="Contabilidad")
    r.h1("1. Recibo de salarios")
    r.table(["Concepto", "Importe"], [["Salario base mensual", eur(row.salary)], ["Comisiones devengadas (octubre)", eur(row.accrual)],
                                     ["Regularización por cancelaciones (clawback)", eur(row.clawback)], ["Total devengado (bruto)", eur(row.gross)]], [4, 2], total=True)
    r.table(["Deducciones", "Importe"], [["Seguridad Social del trabajador (6,48 %)", eur(row.ss_worker)],
                                         [f"Retención IRPF ({num(row.irpf_rate * 100, 0)} %)", eur(row.irpf)],
                                         ["Líquido a percibir", eur(row.net)]], [4, 2], total=True)
    r.small(f"Coste de Seguridad Social a cargo de la empresa (31,60 % sobre el bruto): {eur(row.ss_company)}. Importes en EUR.")
    r.h1("2. Objetivo y cumplimiento del mes")
    r.table(["Magnitud", "Valor"], [["Pólizas vendidas en octubre", str(len(f.sold(O, emp)))], ["Primas vendidas", eur(f.premium(O, emp))],
                                   ["Objetivo de primas", eur(f.target(O, emp))], ["Cumplimiento", pct(f.attainment(O, emp))]], [4, 2])
    r.h1("3. Detalle de comisiones de octubre")
    pol = {p["number"]: p for p in f.policies}
    rows = []
    for c in f.commissions:
        if c["employee"] == emp and c["month"] == O:
            p = pol[c["policy"]]
            rows.append([c["policy"], PRODUCT_ES[p["product"]], "Devengo" if c["kind"] == "ACCRUAL" else "Clawback", eur(c["base"]), eur(c["amount"])])
    r.table(["Póliza", "Producto", "Tipo", "Prima", "Comisión"], rows, [2.2, 1.2, 1.2, 1.4, 1.4], align_right_from=3)
    r.h1("4. Acumulado septiembre-diciembre")
    rows = []
    for m in MONTHS:
        rows.append([MES[m.month].capitalize(), eur(f.accrual(m, emp)), eur(f.clawback(m, emp)), eur(f.accrual(m, emp) + f.clawback(m, emp)), str(len(f.sold(m, emp)))])
    rows.append(["Total", eur(f.accrual(None, emp)), eur(f.clawback(None, emp)), eur(f.accrual(None, emp) + f.clawback(None, emp)), str(len(f.sold(emp=emp)))])
    r.table(["Mes", "Devengado", "Clawback", "Neto", "Pólizas"], rows, [1.6, 1.4, 1.4, 1.4, 1], total=True)
    r.build(path)
