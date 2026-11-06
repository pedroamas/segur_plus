"""Capa de lectura: todo lo que los documentos muestran sale de PostgreSQL (fuente de verdad) y de reglas derivadas.

Reglas derivadas (no están en la BD; son parámetros del modelo, declarados aquí y en generator/README.md):
cotización del trabajador, tipo de IRPF por rol, cotización de la empresa, capital inicial y calendario de pagos.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

from .config import ANCHOR_MONTH, MONTHS
from .dataset import EMPLOYEES, money, next_workday

SS_WORKER = Decimal("0.0648")
SS_COMPANY = Decimal("0.3160")
IRPF = {"MANAGER": Decimal("0.22"), "ACCOUNTANT": Decimal("0.17"), "SALES_MANAGER": Decimal("0.17"), "EMPLOYEE": Decimal("0.13")}
INITIAL_CAPITAL = Decimal("200000.00")
BANK_FEE = (date(2026, 10, 31), Decimal("18.00"))
UNIDENTIFIED_DEPOSIT = (date(2026, 10, 28), Decimal("126.50"))
IN_TRANSIT_RECEIPT = ("FE-2026-0004", date(2026, 11, 2))   # cobro registrado en libros el 20/10 y abonado por el banco el 02/11

MES = {1: "enero", 2: "febrero", 3: "marzo", 4: "abril", 5: "mayo", 6: "junio", 7: "julio", 8: "agosto", 9: "septiembre",
       10: "octubre", 11: "noviembre", 12: "diciembre"}
CATEGORY_ES = {"RENT": "Alquiler", "SOFTWARE": "Software y licencias", "MARKETING": "Marketing", "PROFESSIONAL_SERVICES": "Servicios profesionales",
               "UTILITIES": "Suministros", "SUPPLIES": "Material y consumibles"}
PRODUCT_ES = {"AUTO": "Auto", "HOGAR": "Hogar", "VIDA": "Vida", "SALUD": "Salud"}
ACCOUNT_NAMES = {
    "100": "Capital social", "410": "Acreedores por prestaciones de servicios", "430": "Clientes (aseguradoras)",
    "465": "Remuneraciones pendientes de pago", "4751": "H.P. acreedora por retenciones practicadas",
    "476": "Organismos de la Seguridad Social, acreedores", "572": "Bancos c/c",
    "621": "Arrendamientos y cánones", "623": "Servicios de profesionales independientes", "627": "Publicidad y relaciones públicas",
    "628": "Suministros", "629": "Otros servicios", "640": "Sueldos y salarios", "642": "Seguridad Social a cargo de la empresa",
    "705": "Comisiones de intermediación de seguros",
}


def month_label(m: date) -> str:
    return f"{MES[m.month]} de {m.year}"


def month_end(m: date) -> date:
    return (m.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)


def prev_month(m: date) -> date:
    return (m - timedelta(days=1)).replace(day=1)


def next_month(m: date) -> date:
    return (m.replace(day=28) + timedelta(days=4)).replace(day=1)


def last_workday(m: date) -> date:
    d = month_end(m)
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d


@dataclass(frozen=True)
class User:
    code: str
    full_name: str
    role: str
    department: str
    salary: Decimal
    hire_date: date
    email: str


@dataclass(frozen=True)
class PayRow:
    code: str
    name: str
    role: str
    salary: Decimal
    accrual: Decimal
    clawback: Decimal
    gross: Decimal
    ss_worker: Decimal
    irpf_rate: Decimal
    irpf: Decimal
    net: Decimal
    ss_company: Decimal


@dataclass(frozen=True)
class Cash:
    """Movimiento de tesorería. book_date: fecha en libros; bank_date: fecha en el extracto (None = aún no abonado/cargado)."""
    book_date: date | None
    bank_date: date | None
    concept: str
    ref: str
    amount: Decimal          # + abono / − cargo
    account: str             # contrapartida contable
    kind: str


class Facts:
    def __init__(self, conn):
        self.conn = conn
        q = conn.execute
        self.users = {r[0]: User(*r) for r in q("SELECT employee_code, full_name, role, department, monthly_salary, hire_date, email FROM users ORDER BY employee_code")}
        cols = ("number employee customer customer_name product insurer premium status sale_date start_date renewal_date "
                "cancelled_at rate commission").split()
        self.policies = [dict(zip(cols, r)) for r in q(
            """SELECT p.policy_number, u.employee_code, c.customer_number, c.full_name, p.product, p.insurer, p.premium, p.status,
                      p.sale_date, p.start_date, p.renewal_date, p.cancelled_at, p.insurer_commission_rate, p.insurer_commission_amount
               FROM policies p JOIN users u ON u.id = p.employee_id JOIN customers c ON c.id = p.customer_id
               ORDER BY p.sale_date, p.policy_number""")]
        self.commissions = [dict(zip("policy employee kind month base rate amount".split(), r)) for r in q(
            """SELECT p.policy_number, u.employee_code, c.kind, c.period_month, c.base_amount, c.rate, c.amount
               FROM commissions c JOIN users u ON u.id = c.employee_id JOIN policies p ON p.id = c.policy_id
               ORDER BY c.period_month, p.policy_number, c.kind""")]
        self.invoices = [dict(zip("number direction counterparty issue due month amount status ledger".split(), r)) for r in q(
            """SELECT invoice_number, direction, counterparty, issue_date, due_date, period_month, amount, status, ledger_account
               FROM invoices ORDER BY direction DESC, period_month, invoice_number""")]
        self.expenses = [dict(zip("number date category supplier description amount ledger invoice".split(), r)) for r in q(
            """SELECT e.expense_number, e.expense_date, e.category, e.supplier, e.description, e.amount, e.ledger_account,
                      i.invoice_number FROM expenses e LEFT JOIN invoices i ON i.id = e.invoice_id
               ORDER BY e.expense_date, e.expense_number""")]
        self.targets = {(r[0], r[1]): r[2] for r in q(
            "SELECT u.employee_code, t.period_month, t.target_amount FROM sales_targets t JOIN users u ON u.id = t.employee_id")}

    # --- ventas ----------------------------------------------------------------------------------------------------
    def employee_name(self, code: str) -> str:
        return self.users[code].full_name

    def sold(self, month: date | None = None, emp: str | None = None) -> list[dict]:
        return [p for p in self.policies if (month is None or p["sale_date"].replace(day=1) == month)
                and (emp is None or p["employee"] == emp)]

    def premium(self, month=None, emp=None) -> Decimal:
        return sum((p["premium"] for p in self.sold(month, emp)), Decimal("0"))

    def insurer_commission(self, month=None, emp=None) -> Decimal:
        return sum((p["commission"] for p in self.sold(month, emp)), Decimal("0"))

    def accrual(self, month: date | None = None, emp: str | None = None) -> Decimal:
        return self._comm("ACCRUAL", month, emp)

    def clawback(self, month: date | None = None, emp: str | None = None) -> Decimal:
        return self._comm("CLAWBACK", month, emp)

    def _comm(self, kind, month, emp) -> Decimal:
        return sum((c["amount"] for c in self.commissions if c["kind"] == kind and (month is None or c["month"] == month)
                    and (emp is None or c["employee"] == emp)), Decimal("0"))

    def target(self, month: date, emp: str | None = None) -> Decimal:
        return sum((v for (e, m), v in self.targets.items() if m == month and (emp is None or e == emp)), Decimal("0"))

    @staticmethod
    def pct(num: Decimal, den: Decimal) -> Decimal:
        return (num * 100 / den).quantize(Decimal("0.1")) if den else Decimal("0.0")

    def attainment(self, month: date, emp: str | None = None) -> Decimal:
        return self.pct(self.premium(month, emp), self.target(month, emp))

    def cancelled_in(self, month: date) -> list[dict]:
        return [p for p in self.policies if p["cancelled_at"] and p["cancelled_at"].replace(day=1) == month]

    def active_portfolio(self, upto: date) -> int:
        """Pólizas vendidas hasta fin de `upto` y no canceladas a esa fecha."""
        end = month_end(upto)
        return sum(1 for p in self.policies if p["sale_date"] <= end and not (p["cancelled_at"] and p["cancelled_at"] <= end))

    # --- gastos y facturas -----------------------------------------------------------------------------------------
    def expenses_of(self, month: date) -> list[dict]:
        return [e for e in self.expenses if e["date"].replace(day=1) == month]

    def expense_total(self, month: date) -> Decimal:
        return sum((e["amount"] for e in self.expenses_of(month)), Decimal("0"))

    def expense_by_category(self, month: date) -> dict[str, Decimal]:
        out: dict[str, Decimal] = defaultdict(Decimal)
        for e in self.expenses_of(month):
            out[e["category"]] += e["amount"]
        return dict(out)

    def invoices_of(self, direction: str, month: date | None = None) -> list[dict]:
        return [i for i in self.invoices if i["direction"] == direction and (month is None or i["month"] == month)]

    # --- nóminas ---------------------------------------------------------------------------------------------------
    def payroll(self, month: date) -> list[PayRow]:
        rows = []
        for code, u in self.users.items():
            acc, claw = self.accrual(month, code), self.clawback(month, code)
            gross = money(u.salary + acc + claw)
            ssw = money(gross * SS_WORKER)
            irpf = money(gross * IRPF[u.role])
            rows.append(PayRow(code, u.full_name, u.role, u.salary, acc, claw, gross, ssw, IRPF[u.role], irpf,
                               money(gross - ssw - irpf), money(gross * SS_COMPANY)))
        return rows

    def payroll_totals(self, month: date) -> dict[str, Decimal]:
        rows = self.payroll(month)
        gross = sum((r.gross for r in rows), Decimal("0"))
        return {"gross": gross, "ss_worker": sum((r.ss_worker for r in rows), Decimal("0")),
                "irpf": sum((r.irpf for r in rows), Decimal("0")), "net": sum((r.net for r in rows), Decimal("0")),
                "ss_company_aggregate": money(gross * SS_COMPANY),            # D08: cuota sobre la base total
                "ss_company_by_person": sum((r.ss_company for r in rows), Decimal("0"))}   # D13: suma de cuotas individuales

    # --- tesorería -------------------------------------------------------------------------------------------------
    def cash_movements(self, upto: date) -> list[Cash]:
        out: list[Cash] = [Cash(MONTHS[0], MONTHS[0], "Aportación de socios (ampliación de capital)", "CAP-2026-01", INITIAL_CAPITAL, "100", "CAPITAL")]
        for inv in self.invoices:
            if inv["status"] != "PAID":
                continue
            pay = next_workday(inv["due"])
            if inv["direction"] == "ISSUED":
                bank = pay
                if inv["number"] == IN_TRANSIT_RECEIPT[0]:
                    bank = IN_TRANSIT_RECEIPT[1]
                out.append(Cash(pay, bank, f"Cobro comisiones {inv['counterparty']}", inv["number"], inv["amount"], "430", "RECEIPT"))
            else:
                out.append(Cash(pay, pay, f"Pago a proveedor {inv['counterparty']}", inv["number"], -inv["amount"], "410", "PAYMENT"))
        for e in self.expenses:
            if e["invoice"] is None:
                out.append(Cash(e["date"], e["date"], f"Compra con tarjeta {e['supplier']}", e["number"], -e["amount"], e["ledger"], "CARD"))
        for m in MONTHS:
            t = self.payroll_totals(m)
            out.append(Cash(last_workday(m), last_workday(m), f"Nóminas {MES[m.month]} (lote agregado)", f"NOM-{m:%Y%m}", -t["net"], "465", "PAYROLL"))
            nm = next_month(m)
            irpf_day, ss_day = next_workday(nm.replace(day=20)), last_workday(nm)
            out.append(Cash(irpf_day, irpf_day, f"AEAT retenciones IRPF {MES[m.month]}", f"M111-{m:%Y%m}", -t["irpf"], "4751", "TAX"))
            out.append(Cash(ss_day, ss_day, f"TGSS cotizaciones {MES[m.month]}", f"TC-{m:%Y%m}", -(t["ss_worker"] + t["ss_company_aggregate"]), "476", "TAX"))
        out.append(Cash(None, BANK_FEE[0], "Comisión de mantenimiento de cuenta", "COM-2026-Q4", -BANK_FEE[1], "626", "BANK_FEE"))
        out.append(Cash(None, UNIDENTIFIED_DEPOSIT[0], "Transferencia recibida ref. 77412 (ordenante sin identificar)", "TRF-77412", UNIDENTIFIED_DEPOSIT[1], "?", "UNIDENTIFIED"))
        # movimientos estrictamente posteriores a `upto` quedan fuera de la ventana (en libros y en banco)
        return sorted([c for c in out if (c.book_date or c.bank_date) <= upto or (c.bank_date and c.bank_date <= upto)],
                      key=lambda c: (c.book_date or c.bank_date, c.ref))

    def bank_lines(self, month: date) -> tuple[Decimal, list[Cash], Decimal]:
        """(saldo inicial, movimientos del mes por fecha de banco, saldo final)."""
        end = month_end(month)
        allm = self.cash_movements(date(2027, 1, 15))
        opening = sum((c.amount for c in allm if c.bank_date and c.bank_date < month), Decimal("0"))
        lines = sorted([c for c in allm if c.bank_date and month <= c.bank_date <= end], key=lambda c: (c.bank_date, c.ref))
        return opening, lines, opening + sum((c.amount for c in lines), Decimal("0"))

    def book_cash_balance(self, upto: date) -> Decimal:
        return sum((c.amount for c in self.cash_movements(date(2027, 1, 15)) if c.book_date and c.book_date <= upto), Decimal("0"))

    # --- libro mayor (devengo) -------------------------------------------------------------------------------------
    def journal(self, month: date) -> list[tuple]:
        """Asientos (fecha, cuenta, concepto, debe, haber, asiento) del mes. Nóminas SOLO agregadas (A5)."""
        rows: list[tuple] = []
        n = [0]

        def entry(d, lines):
            n[0] += 1
            for acc, concept, debe, haber in lines:
                rows.append((d, acc, concept, debe, haber, n[0]))

        z = Decimal("0.00")
        end = month_end(month)
        by_ins: dict[str, Decimal] = defaultdict(Decimal)
        for p in self.sold(month):
            by_ins[p["insurer"]] += p["commission"]
        for ins in sorted(by_ins):
            entry(end, [("430", f"Devengo comisiones {month_label(month)} · {ins}", by_ins[ins], z),
                        ("705", f"Comisiones de intermediación · {ins}", z, by_ins[ins])])
        for e in self.expenses_of(month):
            contra = ("410", f"{e['supplier']} · {e['invoice']}") if e["invoice"] else ("572", f"Pago con tarjeta · {e['supplier']}")
            entry(e["date"], [(e["ledger"], f"{e['description']}", e["amount"], z), (contra[0], contra[1], z, e["amount"])])
        t = self.payroll_totals(month)
        pay = last_workday(month)
        entry(pay, [("640", f"Sueldos y salarios {MES[month.month]} (agregado, incl. comisiones netas)", t["gross"], z),
                    ("465", "Remuneraciones pendientes de pago (agregado)", z, t["net"]),
                    ("4751", "Retenciones IRPF practicadas (agregado)", z, t["irpf"]),
                    ("476", "Seguridad Social a cargo del trabajador (agregado)", z, t["ss_worker"])])
        entry(pay, [("642", f"Seguridad Social a cargo de la empresa {MES[month.month]}", t["ss_company_aggregate"], z),
                    ("476", "Cuota empresarial TC1/TC2", z, t["ss_company_aggregate"])])
        for c in self.cash_movements(date(2027, 1, 15)):
            if c.book_date and c.book_date.replace(day=1) == month:
                if c.amount > 0:
                    entry(c.book_date, [("572", c.concept, c.amount, z), (c.account, c.ref, z, c.amount)])
                elif c.kind in ("CARD",):
                    continue  # ya contabilizado con el gasto
                else:
                    entry(c.book_date, [(c.account, c.concept, -c.amount, z), ("572", c.ref, z, -c.amount)])
        return sorted(rows, key=lambda r: (r[0], r[5]))

    def balance_before(self, month: date) -> dict[str, Decimal]:
        """Saldo (debe − haber) por cuenta antes del mes, sumando los asientos de los meses anteriores."""
        bal: dict[str, Decimal] = defaultdict(Decimal)
        for m in MONTHS:
            if m >= month:
                break
            for _, acc, _, d, h, _ in self.journal(m):
                bal[acc] += d - h
        return dict(bal)
