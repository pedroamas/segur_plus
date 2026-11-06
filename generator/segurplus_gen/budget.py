"""Presupuesto 2026 (ejercicio de arranque, septiembre-diciembre). Parámetros de planificación, no datos de la BD,
salvo el presupuesto de primas (= objetivos de ventas) y los sueldos (users.monthly_salary)."""
from __future__ import annotations

from decimal import Decimal

from .config import MONTHS
from .dataset import money
from .facts import SS_COMPANY, Facts

BUDGET_INSURER_RATE = Decimal("0.15")
BUDGET_EMPLOYEE_RATE = Decimal("0.10")
S, O, N, D = MONTHS
EXPENSE_BUDGET = {
    "RENT": {S: "2400.00", O: "2400.00", N: "2400.00", D: "2400.00"},
    "SOFTWARE": {S: "1400.00", O: "1400.00", N: "1400.00", D: "1400.00"},
    "MARKETING": {S: "3000.00", O: "2500.00", N: "3500.00", D: "2500.00"},
    "PROFESSIONAL_SERVICES": {S: "1500.00", O: "1000.00", N: "1000.00", D: "1000.00"},
    "UTILITIES": {S: "450.00", O: "450.00", N: "450.00", D: "450.00"},
    "SUPPLIES": {S: "500.00", O: "300.00", N: "300.00", D: "300.00"},
}


def premium_budget(f: Facts, m) -> Decimal:
    return f.target(m)


def monthly_payroll_base(f: Facts) -> Decimal:
    return sum((u.salary for u in f.users.values()), Decimal("0"))


def budget_rows(f: Facts) -> dict:
    out = {}
    for m in MONTHS:
        prem = premium_budget(f, m)
        out[m] = {"premium": prem, "revenue": money(prem * BUDGET_INSURER_RATE),
                  "expenses": {c: money(v[m]) for c, v in EXPENSE_BUDGET.items()},
                  "salaries": monthly_payroll_base(f), "commissions": money(prem * BUDGET_EMPLOYEE_RATE)}
        out[m]["ss"] = money((out[m]["salaries"] + out[m]["commissions"]) * SS_COMPANY)
    return out
