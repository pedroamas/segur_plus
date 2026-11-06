"""Dataset sintético determinista (semilla fija). Puro Python: no toca la BD ni el reloj del sistema.

Reglas del brief §6: 150-300 altas nuevas de septiembre a diciembre de 2026 sin cartera previa; cancelaciones y
renovaciones solo de esas pólizas; mes ancla octubre con cifras exactas; nada con fecha > 2027-01-15 salvo renovaciones.
"""
from __future__ import annotations

import random
import unicodedata
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal

from .config import BUSINESS_DATE, MONTHS, SEED

NS = uuid.UUID("5e61a7a0-2026-4a10-9c0d-5e67b1a5e001")
EMPLOYEES = ["EMP001", "EMP002", "EMP003", "EMP004"]
CENT = Decimal("0.01")


def money(x) -> Decimal:
    return Decimal(x).quantize(CENT, rounding=ROUND_HALF_UP)


def uid(kind: str, key: str) -> uuid.UUID:
    return uuid.uuid5(NS, f"{kind}:{key}")


# --- catálogos propios (sin Faker) ---------------------------------------------------------------------------------
FIRST = ["Antonio", "María", "José", "Carmen", "Manuel", "Ana", "Francisco", "Isabel", "David", "Laura", "Javier", "Marta",
         "Daniel", "Elena", "Carlos", "Paula", "Miguel", "Lucía", "Alejandro", "Cristina", "Rafael", "Sara", "Pablo", "Raquel",
         "Sergio", "Beatriz", "Jorge", "Patricia", "Adrián", "Rosa", "Álvaro", "Nuria", "Iván", "Teresa", "Rubén", "Silvia",
         "Hugo", "Irene", "Óscar", "Alba", "Enrique", "Pilar", "Gonzalo", "Mónica", "Andrés", "Inés", "Víctor", "Eva"]
SURNAME = ["García", "Rodríguez", "González", "Fernández", "López", "Martínez", "Sánchez", "Pérez", "Gómez", "Martín", "Jiménez",
           "Ruiz", "Hernández", "Díaz", "Moreno", "Muñoz", "Álvarez", "Romero", "Alonso", "Gutiérrez", "Navarro", "Torres",
           "Domínguez", "Vázquez", "Ramos", "Gil", "Ramírez", "Serrano", "Blanco", "Molina", "Morales", "Suárez", "Ortega",
           "Delgado", "Castro", "Ortiz", "Rubio", "Marín", "Sanz", "Iglesias", "Núñez", "Medina", "Garrido", "Cortés", "Castillo"]
CITIES = ["Madrid", "Barcelona", "Valencia", "Sevilla", "Zaragoza", "Málaga", "Murcia", "Palma", "Bilbao", "Alicante", "Córdoba",
          "Valladolid", "Vigo", "Gijón", "Granada", "A Coruña", "Vitoria", "Elche", "Oviedo", "Santander", "Pamplona", "Toledo"]

PRODUCT_RATE = {"AUTO": Decimal("0.1200"), "HOGAR": Decimal("0.1700"), "VIDA": Decimal("0.2000"), "SALUD": Decimal("0.1500")}
PRODUCT_INSURERS = {
    "AUTO": ["Aseguradora Ibérica", "Seguros Atlántico"],
    "HOGAR": ["Aseguradora Ibérica", "Mutua Levante"],
    "VIDA": ["Vida Norte Seguros"],
    "SALUD": ["Salud Mediterránea", "Mutua Levante"],
}
PRODUCT_WEIGHTS = {"AUTO": 38, "HOGAR": 27, "SALUD": 20, "VIDA": 15}
PRODUCT_PREMIUM = {"AUTO": (380, 1150), "HOGAR": (180, 620), "VIDA": (300, 1400), "SALUD": (600, 1500)}
EMPLOYEE_COMMISSION_RATE = Decimal("0.1000")

# Altas por mes y empleado. Octubre es el mes ancla (3 por empleado, importes exactos del brief §6).
POLICIES_PER_MONTH = {
    date(2026, 9, 1): {"EMP001": 7, "EMP002": 6, "EMP003": 6, "EMP004": 5},
    date(2026, 11, 1): {"EMP001": 15, "EMP002": 14, "EMP003": 14, "EMP004": 13},
    date(2026, 12, 1): {"EMP001": 19, "EMP002": 18, "EMP003": 18, "EMP004": 17},
}
OCTOBER_POLICIES = {
    "EMP001": [("AUTO", "1150.00"), ("VIDA", "1200.00"), ("HOGAR", "800.00")],      # 3.150
    "EMP002": [("SALUD", "980.00"), ("AUTO", "760.00"), ("HOGAR", "540.00")],       # 2.280
    "EMP003": [("AUTO", "690.00"), ("HOGAR", "460.00"), ("VIDA", "900.00")],        # 2.050
    "EMP004": [("SALUD", "1100.00"), ("AUTO", "640.00"), ("HOGAR", "460.00")],      # 2.200
}
OCTOBER_TARGETS = {"EMP001": "3000.00", "EMP002": "3000.00", "EMP003": "2800.00", "EMP004": "3200.00"}
HOLIDAYS = {date(2026, 10, 12), date(2026, 12, 8), date(2026, 12, 25)}

# (categoría, proveedor, descripción, cuenta, prefijo factura, plazo de pago en días, factura?, día, {mes: importe})
S, O, N, D = MONTHS
EXPENSE_LINES = [
    ("RENT", "Inmobiliaria Centro Gestión", "Alquiler de oficina Calle Mayor", "621", "ICG", 5, True, 1,
     {S: "2400.00", O: "2400.00", N: "2400.00", D: "2400.00"}),
    ("SOFTWARE", "NubeCRM Suite", "Licencias CRM comercial (4 puestos + gestión)", "629", "NCRM", 15, True, 3,
     {S: "650.00", O: "650.00", N: "650.00", D: "650.00"}),
    ("SOFTWARE", "Atlas Cloud", "Alojamiento y servicios cloud", "629", "ATL", 15, True, 4,
     {S: "420.00", O: "480.00", N: "510.00", D: "495.00"}),
    ("SOFTWARE", "Oficina Digital SL", "Licencias de ofimática y correo", "629", "ODS", 30, True, 5,
     {S: "250.00", O: "250.00", N: "250.00", D: "250.00"}),
    ("MARKETING", "AdClick Media", "Campaña de captación en buscadores", "627", "ADC", 30, True, 8,
     {S: "1500.00", O: "1100.00", N: "1900.00", D: "1300.00"}),
    ("MARKETING", "Social Impulso", "Campaña en redes sociales y contenidos", "627", "SOI", 30, True, 12,
     {S: "1100.00", O: "800.00", N: "1400.00", D: "900.00"}),
    ("PROFESSIONAL_SERVICES", "Gestoría Pérez y Asociados", "Asesoría fiscal, laboral y contable", "623", "GPA", 30, True, 15,
     {S: "600.00", O: "600.00", N: "600.00", D: "600.00"}),
    ("PROFESSIONAL_SERVICES", "Bufete Lara Legal", "Asesoría jurídica y protección de datos", "623", "BLL", 30, True, 16,
     {S: "900.00", O: "250.00", N: "250.00", D: "250.00"}),
    ("UTILITIES", "Energía Hogar Luz", "Suministro eléctrico de oficina", "628", "EHL", 20, True, 10,
     {S: "210.00", O: "230.00", N: "245.00", D: "270.00"}),
    ("UTILITIES", "Fibra Nova Telecom", "Internet y telefonía", "628", "FNT", 20, True, 10,
     {S: "190.00", O: "190.00", N: "190.00", D: "190.00"}),
    ("SUPPLIES", "Papelería Central", "Material de oficina", "629", "PAC", 30, True, 18,
     {S: "520.00", O: "180.00", N: "160.00", D: "210.00"}),
    ("SUPPLIES", "Aguas del Norte", "Agua y cafetería (compra con tarjeta)", "629", "AGN", 0, False, 20,
     {S: "110.00", O: "120.00", N: "125.00", D: "140.00"}),
]
INSURER_PAYMENT_DAYS = 15
SALES_INVOICE_DAY = 5
LEDGER_REVENUE = "705"


@dataclass
class Customer:
    number: str
    full_name: str
    email: str
    city: str
    created_on: date


@dataclass
class Policy:
    number: str
    employee_code: str
    customer_number: str
    product: str
    insurer: str
    premium: Decimal
    status: str
    sale_date: date
    start_date: date
    renewal_date: date
    cancelled_at: date | None
    rate: Decimal
    commission_amount: Decimal


@dataclass
class Commission:
    policy_number: str
    employee_code: str
    kind: str
    period_month: date
    base_amount: Decimal
    rate: Decimal
    amount: Decimal


@dataclass
class Invoice:
    number: str
    direction: str
    counterparty: str
    issue_date: date
    due_date: date
    period_month: date
    amount: Decimal
    status: str
    ledger_account: str


@dataclass
class Expense:
    number: str
    expense_date: date
    category: str
    supplier: str
    description: str
    amount: Decimal
    ledger_account: str
    invoice_number: str | None


@dataclass
class Target:
    employee_code: str
    period_month: date
    amount: Decimal


@dataclass
class Dataset:
    customers: list[Customer] = field(default_factory=list)
    policies: list[Policy] = field(default_factory=list)
    commissions: list[Commission] = field(default_factory=list)
    invoices: list[Invoice] = field(default_factory=list)
    expenses: list[Expense] = field(default_factory=list)
    targets: list[Target] = field(default_factory=list)


def _ascii(s: str) -> str:
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower().replace(" ", "")


def _month_end(m: date) -> date:
    return (m.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)


def _workdays(m: date) -> list[date]:
    d, out = m, []
    while d <= _month_end(m):
        if d.weekday() < 5 and d not in HOLIDAYS:
            out.append(d)
        d += timedelta(days=1)
    return out


def _day_weights(m: date, days: list[date]) -> list[float]:
    w = []
    for d in days:
        x = 1.0
        if m.month == 11 and 23 <= d.day <= 27:
            x = 2.6          # Black Friday
        if m.month == 12:
            x = 1.8 if d.day <= 22 else 0.5
        if m.month == 9 and d.day <= 7:
            x = 0.5          # arranque
        w.append(x)
    return w


def next_workday(d: date) -> date:
    while d.weekday() >= 5 or d in HOLIDAYS:
        d += timedelta(days=1)
    return d


def _add_year(d: date) -> date:
    return d.replace(year=d.year + 1)


def _premium(rng: random.Random, product: str) -> Decimal:
    lo, hi = PRODUCT_PREMIUM[product]
    return money(Decimal(rng.randrange(lo * 20, hi * 20)) / 20)


def build_dataset(seed: int = SEED) -> Dataset:
    rng = random.Random(seed)
    ds = Dataset()

    # 1) altas (sin números definitivos todavía)
    raw: list[dict] = []
    for emp, items in OCTOBER_POLICIES.items():
        days = rng.sample(_workdays(date(2026, 10, 1)), 3)
        for (product, premium), day in zip(items, sorted(days)):
            raw.append(dict(emp=emp, product=product, premium=money(premium), sale=day))
    for month, per_emp in POLICIES_PER_MONTH.items():
        days = _workdays(month)
        weights = _day_weights(month, days)
        for emp in EMPLOYEES:
            for _ in range(per_emp[emp]):
                product = rng.choices(list(PRODUCT_WEIGHTS), weights=list(PRODUCT_WEIGHTS.values()))[0]
                raw.append(dict(emp=emp, product=product, premium=_premium(rng, product), sale=rng.choices(days, weights)[0]))
    raw.sort(key=lambda r: (r["sale"], r["emp"], r["product"], r["premium"]))

    # 2) clientes (≈12 % de reutilización), insurers, fechas, cancelaciones
    custs: list[Customer] = []
    for n, r in enumerate(raw, 1):
        if custs and rng.random() < 0.12:
            c = rng.choice(custs)
        else:
            f, s1, s2 = rng.choice(FIRST), rng.choice(SURNAME), rng.choice(SURNAME)
            c = Customer(f"CLI-{len(custs) + 1:05d}", f"{f} {s1} {s2}", f"{_ascii(f)}.{_ascii(s1)}{len(custs) + 1}@correo.example",
                         rng.choice(CITIES), r["sale"])
            custs.append(c)
        insurer = rng.choice(PRODUCT_INSURERS[r["product"]])
        start = min(r["sale"] + timedelta(days=rng.randint(0, 5)), date(2026, 12, 31))
        rate = PRODUCT_RATE[r["product"]]
        r.update(num=f"POL-2026-{n:06d}", cust=c.number, insurer=insurer, start=start, rate=rate, cancelled=None)
    ds.customers = custs

    def eligible(r, emp=None, month=None):
        return r["cancelled"] is None and (emp is None or r["emp"] == emp) and (month is None or r["sale"].month == month)

    # cancelaciones forzadas: dos pólizas de septiembre canceladas en octubre y una de octubre cancelada en diciembre
    for emp in ("EMP001", "EMP003"):
        pick = next(r for r in raw if eligible(r, emp, 9) and r["sale"].day >= 14)
        pick["cancelled"] = date(2026, 10, rng.randint(6, 27))
    oct_pick = next(r for r in raw if eligible(r, "EMP003", 10) and r["product"] == "AUTO")
    oct_pick["cancelled"] = date(2026, 12, 14)
    for r in raw:                                   # cancelaciones aleatorias (≈12 %), nunca después del 31/12
        if r["cancelled"] is None and r["sale"] <= date(2026, 12, 10) and rng.random() < 0.12:
            c = r["start"] + timedelta(days=rng.randint(12, 45))
            if c <= date(2026, 12, 31):
                r["cancelled"] = c

    for r in raw:
        comm = money(r["premium"] * r["rate"])
        ds.policies.append(Policy(r["num"], r["emp"], r["cust"], r["product"], r["insurer"], r["premium"],
                                  "CANCELLED" if r["cancelled"] else "ACTIVE", r["sale"], r["start"], _add_year(r["start"]),
                                  r["cancelled"], r["rate"], comm))
        base = r["premium"]
        amount = money(base * EMPLOYEE_COMMISSION_RATE)
        ds.commissions.append(Commission(r["num"], r["emp"], "ACCRUAL", r["sale"].replace(day=1), base, EMPLOYEE_COMMISSION_RATE, amount))
        if r["cancelled"]:
            ds.commissions.append(Commission(r["num"], r["emp"], "CLAWBACK", r["cancelled"].replace(day=1), base,
                                             EMPLOYEE_COMMISSION_RATE, -amount))

    # 3) objetivos: octubre fijo (A4); resto proporcional a la escala de ventas con cumplimientos variados
    for m in MONTHS:
        for emp in EMPLOYEES:
            if m == date(2026, 10, 1):
                ds.targets.append(Target(emp, m, money(OCTOBER_TARGETS[emp])))
                continue
            sold = sum(p.premium for p in ds.policies if p.employee_code == emp and p.sale_date.replace(day=1) == m)
            ratio = Decimal(str(rng.choice([0.78, 0.92, 1.0, 1.08, 1.2])))
            target = (sold / ratio / 50).quantize(Decimal("1"), rounding=ROUND_HALF_UP) * 50
            ds.targets.append(Target(emp, m, money(target)))

    # 4) gastos y facturas recibidas
    for m in MONTHS:
        for i, (cat, supplier, desc, ledger, prefix, terms, has_inv, day, amounts) in enumerate(EXPENSE_LINES, 1):
            amount = money(amounts[m])
            edate = next_workday(m.replace(day=day))
            inv_no = None
            if has_inv:
                inv_no = f"{prefix}-{m:%Y%m}-{i:02d}"
                natural_due = edate + timedelta(days=terms)
                paid = natural_due < BUSINESS_DATE
                ds.invoices.append(Invoice(inv_no, "RECEIVED", supplier, edate, natural_due if paid else BUSINESS_DATE, m, amount,
                                           "PAID" if paid else "OPEN", ledger))
            ds.expenses.append(Expense(f"GAS-{m:%Y%m}-{i:02d}", edate, cat, supplier, f"{desc} ({_MES[m.month]} 2026)", amount, ledger, inv_no))

    # 5) facturas emitidas a las aseguradoras (devengo en el mes de venta; se facturan a primeros del mes siguiente)
    seq = 0
    for m in MONTHS:
        nxt = (m.replace(day=28) + timedelta(days=4)).replace(day=1)
        issue = next_workday(nxt.replace(day=SALES_INVOICE_DAY))
        by_insurer: dict[str, Decimal] = {}
        for p in ds.policies:
            if p.sale_date.replace(day=1) == m:
                by_insurer[p.insurer] = by_insurer.get(p.insurer, Decimal("0")) + p.commission_amount
        for insurer in sorted(by_insurer):
            seq += 1
            natural_due = issue + timedelta(days=INSURER_PAYMENT_DAYS)
            paid = natural_due < BUSINESS_DATE and not (m == date(2026, 11, 1) and insurer == "Mutua Levante")
            ds.invoices.append(Invoice(f"FE-2026-{seq:04d}", "ISSUED", insurer, issue, min(natural_due, BUSINESS_DATE), m,
                                       money(by_insurer[insurer]), "PAID" if paid else "OPEN", LEDGER_REVENUE))
    return ds


_MES = {1: "enero", 2: "febrero", 3: "marzo", 4: "abril", 5: "mayo", 6: "junio", 7: "julio", 8: "agosto", 9: "septiembre",
        10: "octubre", 11: "noviembre", 12: "diciembre"}


def timestamp(d: date) -> datetime:
    from .config import SEED_TS
    return SEED_TS.replace(year=d.year, month=d.month, day=d.day, hour=9)
