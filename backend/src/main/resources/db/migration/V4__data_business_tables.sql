-- Generado desde docs/data-model/schema.md (fuente de diseño). Una migración por paquete.

CREATE TABLE customers (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_number varchar(16)  NOT NULL UNIQUE,
  full_name       varchar(120) NOT NULL,
  email           varchar(254),
  city            varchar(80),
  created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE policies (
  id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_number             varchar(24)  NOT NULL UNIQUE,
  employee_id               uuid NOT NULL REFERENCES users(id),      -- vendedor
  customer_id               uuid NOT NULL REFERENCES customers(id),
  product                   varchar(8)   NOT NULL CHECK (product IN ('AUTO','HOGAR','VIDA','SALUD')),
  insurer                   varchar(60)  NOT NULL,                   -- aseguradora como texto
  premium                   numeric(14,2) NOT NULL CHECK (premium > 0),  -- prima anual
  currency                  char(3)      NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status                    varchar(10)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','CANCELLED','EXPIRED')),
  sale_date                 date NOT NULL,
  start_date                date NOT NULL,
  renewal_date              date NOT NULL,
  cancelled_at              date,
  insurer_commission_rate   numeric(5,4) NOT NULL CHECK (insurer_commission_rate BETWEEN 0 AND 1),
  insurer_commission_amount numeric(14,2) NOT NULL,
  created_at                timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_policies_dates CHECK (start_date >= sale_date AND renewal_date > start_date),
  CONSTRAINT ck_policies_cancel CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
  CONSTRAINT ck_policies_insurer_commission CHECK (insurer_commission_amount = round(premium * insurer_commission_rate, 2))
);
CREATE INDEX ix_policies_employee_sale ON policies (employee_id, sale_date);
CREATE INDEX ix_policies_customer ON policies (customer_id);
CREATE INDEX ix_policies_sale_date ON policies (sale_date);
CREATE INDEX ix_policies_status ON policies (status);

CREATE TABLE commissions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id  uuid NOT NULL REFERENCES users(id),
  policy_id    uuid NOT NULL REFERENCES policies(id),
  kind         varchar(8) NOT NULL DEFAULT 'ACCRUAL' CHECK (kind IN ('ACCRUAL','CLAWBACK')),
  period_month date NOT NULL CHECK (period_month = date_trunc('month', period_month)::date),
  base_amount  numeric(14,2) NOT NULL CHECK (base_amount > 0),
  rate         numeric(5,4)  NOT NULL CHECK (rate BETWEEN 0 AND 1),
  amount       numeric(14,2) NOT NULL,
  currency     char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_commissions_policy_kind UNIQUE (policy_id, kind),
  CONSTRAINT ck_commissions_amount CHECK (
    (kind = 'ACCRUAL'  AND amount = round(base_amount * rate, 2)) OR
    (kind = 'CLAWBACK' AND amount = -round(base_amount * rate, 2)))
);
CREATE INDEX ix_commissions_employee_period ON commissions (employee_id, period_month);

CREATE TABLE invoices (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  invoice_number varchar(24) NOT NULL UNIQUE,
  direction      varchar(8)  NOT NULL CHECK (direction IN ('ISSUED','RECEIVED')),
  counterparty   varchar(120) NOT NULL,
  issue_date     date NOT NULL,
  due_date       date NOT NULL,
  period_month   date NOT NULL CHECK (period_month = date_trunc('month', period_month)::date),
  amount         numeric(14,2) NOT NULL CHECK (amount >= 0),
  currency       char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status         varchar(6) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PAID','VOID')),
  ledger_account varchar(8) NOT NULL,                      -- cuenta del plan contable
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_invoices_due CHECK (due_date >= issue_date)
);
CREATE INDEX ix_invoices_period ON invoices (period_month, direction);

CREATE TABLE expenses (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  expense_number varchar(16) NOT NULL UNIQUE,
  expense_date   date NOT NULL,
  category       varchar(24) NOT NULL
                 CHECK (category IN ('RENT','SOFTWARE','MARKETING','PROFESSIONAL_SERVICES','UTILITIES','SUPPLIES')),
  supplier       varchar(120) NOT NULL,
  description    varchar(200) NOT NULL,
  amount         numeric(14,2) NOT NULL CHECK (amount >= 0),
  currency       char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status         varchar(8) NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('DRAFT','APPROVED','VOID')),
  ledger_account varchar(8) NOT NULL,                      -- enlace contable por columna
  invoice_id     uuid REFERENCES invoices(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_expenses_ledger CHECK (status = 'DRAFT' OR ledger_account <> '')
);
CREATE INDEX ix_expenses_date ON expenses (expense_date);
CREATE INDEX ix_expenses_invoice ON expenses (invoice_id);

CREATE TABLE sales_targets (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id   uuid NOT NULL REFERENCES users(id),
  period_month  date NOT NULL CHECK (period_month = date_trunc('month', period_month)::date),
  target_amount numeric(14,2) NOT NULL CHECK (target_amount > 0),   -- objetivo de primas
  currency      char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  CONSTRAINT uq_sales_targets UNIQUE (employee_id, period_month)
);
