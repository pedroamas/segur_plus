"""Criterios F2 1 y 5: anclas de octubre exactas leyendo la BD y ninguna fecha posterior a 2027-01-15 (salvo renovaciones)."""
from datetime import date
from decimal import Decimal

from segurplus_gen.config import BUSINESS_DATE


def one(conn, sql, *params):
    return conn.execute(sql, params).fetchone()[0]


OCT_FROM, OCT_TO = date(2026, 10, 1), date(2026, 10, 31)


def test_october_anchor_policies_and_premiums(conn):
    assert one(conn, "SELECT count(*) FROM policies WHERE sale_date BETWEEN %s AND %s", OCT_FROM, OCT_TO) == 12
    assert one(conn, "SELECT sum(premium) FROM policies WHERE sale_date BETWEEN %s AND %s", OCT_FROM, OCT_TO) == Decimal("9680.00")
    rows = conn.execute("""SELECT u.employee_code, count(*), sum(p.premium) FROM policies p JOIN users u ON u.id = p.employee_id
                           WHERE p.sale_date BETWEEN %s AND %s GROUP BY 1 ORDER BY 1""", (OCT_FROM, OCT_TO)).fetchall()
    assert rows == [("EMP001", 3, Decimal("3150.00")), ("EMP002", 3, Decimal("2280.00")),
                    ("EMP003", 3, Decimal("2050.00")), ("EMP004", 3, Decimal("2200.00"))]


def test_october_anchor_commissions_expenses_targets(conn):
    assert one(conn, "SELECT sum(amount) FROM commissions WHERE kind='ACCRUAL' AND period_month=%s", OCT_FROM) == Decimal("968.00")
    rows = conn.execute("""SELECT u.employee_code, sum(c.amount) FROM commissions c JOIN users u ON u.id = c.employee_id
                           WHERE c.kind='ACCRUAL' AND c.period_month=%s GROUP BY 1 ORDER BY 1""", (OCT_FROM,)).fetchall()
    assert rows == [("EMP001", Decimal("315.00")), ("EMP002", Decimal("228.00")), ("EMP003", Decimal("205.00")), ("EMP004", Decimal("220.00"))]
    assert one(conn, "SELECT sum(amount) FROM expenses WHERE expense_date BETWEEN %s AND %s", OCT_FROM, OCT_TO) == Decimal("7250.00")
    assert one(conn, "SELECT sum(target_amount) FROM sales_targets WHERE period_month=%s", OCT_FROM) == Decimal("12000.00")
    rows = conn.execute("""SELECT u.employee_code, t.target_amount,
                                  round(100 * (SELECT sum(premium) FROM policies p WHERE p.employee_id = t.employee_id
                                               AND p.sale_date BETWEEN %s AND %s) / t.target_amount, 1)
                           FROM sales_targets t JOIN users u ON u.id = t.employee_id WHERE t.period_month=%s ORDER BY 1""",
                        (OCT_FROM, OCT_TO, OCT_FROM)).fetchall()
    assert rows == [("EMP001", Decimal("3000.00"), Decimal("105.0")), ("EMP002", Decimal("3000.00"), Decimal("76.0")),
                    ("EMP003", Decimal("2800.00"), Decimal("73.2")), ("EMP004", Decimal("3200.00"), Decimal("68.8"))]
    assert one(conn, "SELECT round(100 * 9680 / sum(target_amount), 1) FROM sales_targets WHERE period_month=%s", OCT_FROM) == Decimal("80.7")


def test_new_policies_between_150_and_300_from_september_to_december(conn):
    n = one(conn, "SELECT count(*) FROM policies")
    assert 150 <= n <= 300
    lo, hi = conn.execute("SELECT min(sale_date), max(sale_date) FROM policies").fetchone()
    assert lo >= date(2026, 9, 1) and hi <= date(2026, 12, 31)


def test_cancellations_only_of_generated_policies_and_clawbacks_consistent(conn):
    assert one(conn, "SELECT count(*) FROM policies WHERE status='CANCELLED'") >= 3
    assert one(conn, """SELECT count(*) FROM commissions c JOIN policies p ON p.id = c.policy_id
                        WHERE c.kind='CLAWBACK' AND (p.cancelled_at IS NULL OR c.period_month <> date_trunc('month', p.cancelled_at)::date)""") == 0
    # una póliza de septiembre cancelada en octubre no altera las cifras ancla (se cuentan por sale_date)
    assert one(conn, "SELECT count(*) FROM policies WHERE sale_date < '2026-10-01' AND cancelled_at BETWEEN '2026-10-01' AND '2026-10-31'") >= 1


def test_no_data_after_business_date_except_renewals(conn):
    cutoff = BUSINESS_DATE
    cols = conn.execute("""SELECT table_name, column_name, data_type FROM information_schema.columns
                           WHERE table_schema='public' AND data_type IN ('date','timestamp with time zone','timestamp without time zone')
                             AND table_name IN ('customers','policies','commissions','invoices','expenses','sales_targets','documents')
                             AND NOT (table_name='policies' AND column_name='renewal_date')""").fetchall()
    assert cols
    for table, col, dtype in cols:
        limit = cutoff if dtype == "date" else f"{cutoff} 23:59:59+01"   # instantes: hasta el final del día de negocio
        bad = one(conn, f'SELECT count(*) FROM {table} WHERE "{col}" > %s::{dtype}', limit)
        assert bad == 0, f"{table}.{col} tiene fechas posteriores a {cutoff}"
    assert one(conn, "SELECT min(renewal_date) FROM policies") > cutoff   # las renovaciones sí son futuras


def test_monthly_salary_filled_for_seven_users(conn):
    rows = conn.execute("SELECT employee_code, monthly_salary FROM users ORDER BY 1").fetchall()
    assert len(rows) == 7 and all(s > 0 for _, s in rows)
    assert dict(rows)["DIR001"] == Decimal("6500.00")
