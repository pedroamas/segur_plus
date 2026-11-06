"""Carga del dataset en PostgreSQL (fuente de verdad). Reset idempotente de las tablas de datos y de documentos."""
from __future__ import annotations

import psycopg

from . import config
from .dataset import Dataset, timestamp, uid

DATA_TABLES = "commissions, policies, expenses, invoices, sales_targets, customers"


def connect() -> psycopg.Connection:
    return psycopg.connect(**config.db_conninfo(), autocommit=True)


def user_ids(conn) -> dict[str, str]:
    rows = conn.execute("SELECT employee_code, id FROM users ORDER BY employee_code").fetchall()
    return {code: str(i) for code, i in rows}


def load_dataset(conn: psycopg.Connection, ds: Dataset) -> None:
    """Sustituye TODO el dataset de negocio (no toca users salvo monthly_salary, ni auditoría ni refresh_tokens)."""
    users = user_ids(conn)
    missing = set(config.SALARIES) - set(users)
    if missing:
        raise RuntimeError(f"Faltan usuarios sembrados por el backend: {sorted(missing)} (arranca el backend con la semilla de dev)")
    with conn.transaction():
        for code, salary in config.SALARIES.items():
            conn.execute("UPDATE users SET monthly_salary = %s WHERE employee_code = %s", (salary, code))
        conn.execute(f"TRUNCATE {DATA_TABLES}")
        cur = conn.cursor()
        cur.executemany(
            "INSERT INTO customers (id, customer_number, full_name, email, city, created_at) VALUES (%s,%s,%s,%s,%s,%s)",
            [(uid("customer", c.number), c.number, c.full_name, c.email, c.city, timestamp(c.created_on)) for c in ds.customers])
        cur.executemany(
            """INSERT INTO policies (id, policy_number, employee_id, customer_id, product, insurer, premium, status, sale_date,
               start_date, renewal_date, cancelled_at, insurer_commission_rate, insurer_commission_amount, created_at)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            [(uid("policy", p.number), p.number, users[p.employee_code], uid("customer", p.customer_number), p.product, p.insurer,
              p.premium, p.status, p.sale_date, p.start_date, p.renewal_date, p.cancelled_at, p.rate, p.commission_amount,
              timestamp(p.sale_date)) for p in ds.policies])
        cur.executemany(
            """INSERT INTO commissions (id, employee_id, policy_id, kind, period_month, base_amount, rate, amount, created_at)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            [(uid("commission", f"{c.policy_number}:{c.kind}"), users[c.employee_code], uid("policy", c.policy_number), c.kind,
              c.period_month, c.base_amount, c.rate, c.amount, timestamp(c.period_month)) for c in ds.commissions])
        cur.executemany(
            """INSERT INTO invoices (id, invoice_number, direction, counterparty, issue_date, due_date, period_month, amount,
               status, ledger_account, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            [(uid("invoice", i.number), i.number, i.direction, i.counterparty, i.issue_date, i.due_date, i.period_month,
              i.amount, i.status, i.ledger_account, timestamp(i.issue_date)) for i in ds.invoices])
        cur.executemany(
            """INSERT INTO expenses (id, expense_number, expense_date, category, supplier, description, amount, status,
               ledger_account, invoice_id, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,'APPROVED',%s,%s,%s)""",
            [(uid("expense", e.number), e.number, e.expense_date, e.category, e.supplier, e.description, e.amount,
              e.ledger_account, uid("invoice", e.invoice_number) if e.invoice_number else None, timestamp(e.expense_date))
             for e in ds.expenses])
        cur.executemany(
            "INSERT INTO sales_targets (id, employee_id, period_month, target_amount) VALUES (%s,%s,%s,%s)",
            [(uid("target", f"{t.employee_code}:{t.period_month}"), users[t.employee_code], t.period_month, t.amount)
             for t in ds.targets])
