# Esquema PostgreSQL (documentación, no ejecutable)

El DDL es **documentación de diseño**; las migraciones Flyway reales se escriben en F1. ERD: [erd.md](erd.md). **11 tablas**, sin particionado, sin triggers y sin `EXCLUDE`.

## Convenciones
| Tema | Regla |
|---|---|
| Claves | PK `uuid` con `gen_random_uuid()`. Excepción: `audit_logs` (`bigint` identity). Los IDs expuestos por la API son estos UUID. |
| Importes | `NUMERIC(14,2)` + columna `currency char(3)` (ISO 4217, por defecto `EUR`). No hay precios de IA, así que no hace falta más precisión. |
| Tasas | `NUMERIC(5,4)` entre 0 y 1 (0,1000 = 10 %). |
| Fechas | Fechas de negocio (venta, inicio, periodo) `date`; los periodos mensuales son `date` con día 1 (CHECK). Instantes `timestamptz`. |
| Reloj | La aplicación rellena `created_at`/`updated_at` con el `Clock` inyectable (ADR 0006); `DEFAULT now()` es solo red de seguridad. `audit_logs` guarda `occurred_at` (tiempo real) y `business_date` (fecha ficticia). |
| Enumeraciones | `text` + `CHECK (col IN (...))`. |
| Borrado | Soft delete (`deleted_at`) solo en `documents` y `conversations`. Lo demás no se borra. |
| FK | `ON DELETE RESTRICT` por defecto; cada FK tiene índice. |
| Sensibles | `users.monthly_salary` solo lo lee el generador de nóminas: ningún DTO, endpoint ni herramienta lo expone. `messages.content` es el único contenido de conversación y nunca va a `audit_logs` ni a logs. |

## Tablas por paquete
| Paquete | Tablas |
|---|---|
| `auth` | `users` |
| `documents` | `documents` |
| `ai` | `conversations`, `messages` |
| `data` | `customers`, `policies`, `commissions`, `invoices`, `expenses`, `sales_targets` |
| `audit` | `audit_logs` |

---
## auth

### users
Una sola tabla: identidad, rol (enum), departamento y datos para generar nóminas. Sin tablas de roles, permisos, empleados ni departamentos.
```sql
CREATE TABLE users (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_code  varchar(10)  NOT NULL UNIQUE,   -- DIR001, ACC001, COM001, EMP001-004
  email          varchar(254) NOT NULL UNIQUE,
  password_hash  varchar(100) NOT NULL,          -- Argon2id o bcrypt
  full_name      varchar(120) NOT NULL,
  role           varchar(16)  NOT NULL
                 CHECK (role IN ('MANAGER','ACCOUNTANT','SALES_MANAGER','EMPLOYEE')),
  department     varchar(16)  NOT NULL
                 CHECK (department IN ('DIRECCION','CONTABILIDAD','COMERCIAL')),
  monthly_salary numeric(14,2) NOT NULL CHECK (monthly_salary > 0),   -- solo para generar nóminas
  currency       char(3)      NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  hire_date      date         NOT NULL,
  dify_user_ref  varchar(40)  NOT NULL UNIQUE CHECK (dify_user_ref ~ '^u_[0-9a-f-]{36}$'),  -- opaco, distinto de id
  active         boolean      NOT NULL DEFAULT true,
  created_at     timestamptz  NOT NULL DEFAULT now()
);
```

---
## documents

### documents
Metadatos del documento y regla de acceso. El binario está en MinIO (`storage_key`); el texto indexado, en Dify (`dify_*`).
```sql
CREATE TABLE documents (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code             varchar(8)   NOT NULL UNIQUE,          -- D01-D29, G01-G03
  title            varchar(200) NOT NULL,
  file_type        varchar(8)   NOT NULL CHECK (file_type IN ('PDF','XLSX','CSV','JSON')),
  category         varchar(24)  NOT NULL
                   CHECK (category IN ('REPORT','STRATEGY','FINANCE','CONTRACT','PAYROLL','COMMERCIAL_ACTIVITY','MANUAL','POLICY')),
  kb               varchar(12)  NOT NULL CHECK (kb IN ('GENERAL','DIRECCION','FINANZAS','EMPLEADOS')),
  classification   varchar(12)  NOT NULL CHECK (classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
  owner_id         uuid REFERENCES users(id),             -- NULL en documentos corporativos
  allowed_roles    text[]       NOT NULL,                 -- roles con READ/DOWNLOAD
  storage_key      varchar(200) NOT NULL UNIQUE,          -- clave en MinIO
  checksum_sha256  char(64)     NOT NULL,
  size_bytes       bigint       NOT NULL CHECK (size_bytes > 0),
  index_status     varchar(10)  NOT NULL DEFAULT 'PENDING'
                   CHECK (index_status IN ('PENDING','INDEXING','INDEXED','FAILED')),
  dify_dataset_id  varchar(64),
  dify_document_id varchar(64),
  indexed_at       timestamptz,
  created_by       uuid NOT NULL REFERENCES users(id),
  created_at       timestamptz  NOT NULL DEFAULT now(),
  updated_at       timestamptz  NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  -- roles válidos y MANAGER siempre incluido (administra todo)
  CONSTRAINT ck_documents_roles CHECK (
    allowed_roles <@ ARRAY['MANAGER','ACCOUNTANT','SALES_MANAGER','EMPLOYEE']::text[]
    AND 'MANAGER' = ANY (allowed_roles)),
  -- RESTRICTED nunca por rol de empleado (solo por propietario)
  CONSTRAINT ck_documents_restricted CHECK (
    classification <> 'RESTRICTED' OR NOT ('EMPLOYEE' = ANY (allowed_roles))),
  -- KB-Empleados: siempre con propietario y sin acceso por el rol EMPLOYEE (red de seguridad EMP↔EMP)
  CONSTRAINT ck_documents_empleados CHECK (
    kb <> 'EMPLEADOS' OR (owner_id IS NOT NULL AND NOT ('EMPLOYEE' = ANY (allowed_roles)))),
  CONSTRAINT ck_documents_indexed CHECK (index_status <> 'INDEXED' OR (dify_document_id IS NOT NULL AND indexed_at IS NOT NULL))
);
CREATE INDEX ix_documents_kb ON documents (kb) WHERE deleted_at IS NULL;
CREATE INDEX ix_documents_owner ON documents (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_documents_created_by ON documents (created_by);
CREATE INDEX ix_documents_roles ON documents USING gin (allowed_roles);
CREATE INDEX ix_documents_index_status ON documents (index_status) WHERE deleted_at IS NULL;
```
Regla de acceso (ADR 0002): `READ`/`DOWNLOAD` ⇔ `deleted_at IS NULL AND (:role = ANY(allowed_roles) OR owner_id = :userId)`. Esa misma condición es el `WHERE` de los listados.

---
## ai

### conversations
```sql
CREATE TABLE conversations (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               uuid NOT NULL REFERENCES users(id),   -- propietario; solo él la ve
  title                 varchar(120),
  dify_conversation_id  varchar(64) UNIQUE,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  deleted_at            timestamptz
);
CREATE INDEX ix_conversations_user ON conversations (user_id, updated_at DESC) WHERE deleted_at IS NULL;
```

### messages
Sin precios ni tablas de precios. Las fuentes guardan referencias (id de documento, fragmento), no texto.
```sql
CREATE TABLE messages (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES conversations(id),
  role            varchar(9)  NOT NULL CHECK (role IN ('USER','ASSISTANT')),
  content         text        NOT NULL DEFAULT '',
  sources         jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- [{documentId, chunk}] ya re-autorizadas
  tools           jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- [{tool, params, rows}]
  tokens_in       integer     CHECK (tokens_in >= 0),
  tokens_out      integer     CHECK (tokens_out >= 0),
  latency_ms      integer     CHECK (latency_ms >= 0),
  status          varchar(10) NOT NULL DEFAULT 'COMPLETED'
                  CHECK (status IN ('PENDING','STREAMING','COMPLETED','FAILED','CANCELLED')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_messages_sources_array CHECK (jsonb_typeof(sources) = 'array' AND jsonb_typeof(tools) = 'array')
);
CREATE INDEX ix_messages_conversation ON messages (conversation_id, created_at);
```

---
## data

### customers
```sql
CREATE TABLE customers (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_number varchar(16)  NOT NULL UNIQUE,
  full_name       varchar(120) NOT NULL,
  email           varchar(254),
  city            varchar(80),
  created_at      timestamptz  NOT NULL DEFAULT now()
);
```

### policies
La venta **es** la póliza: empleado vendedor, cliente, producto y la comisión que el asegurador paga al corredor (A1). Las ventas se cuentan por `sale_date`, con independencia del estado actual de la póliza (las cancelaciones no reescriben los meses cerrados).
```sql
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
```

### commissions
Comisión del **empleado** por póliza. Una cancelación genera una fila `CLAWBACK` negativa en el mes de la cancelación; la fila original no cambia.
```sql
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
```

### invoices
Emitidas (a la aseguradora, por la liquidación de comisiones) y recibidas (de proveedores). El enlace contable es una columna (`ledger_account`), no una tabla de asientos.
```sql
CREATE TABLE invoices (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  invoice_number varchar(24) NOT NULL UNIQUE,
  direction      varchar(8)  NOT NULL CHECK (direction IN ('ISSUED','RECEIVED')),
  counterparty   varchar(120) NOT NULL,
  issue_date     date NOT NULL,
  due_date       date NOT NULL,
  period_month   date NOT NULL CHECK (period_month = date_trunc('month', period_month)::date),
  net_amount     numeric(14,2) NOT NULL CHECK (net_amount >= 0),
  tax_amount     numeric(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
  total_amount   numeric(14,2) NOT NULL,
  currency       char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status         varchar(6) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PAID','VOID')),
  ledger_account varchar(8) NOT NULL,                      -- cuenta del plan contable
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_invoices_total CHECK (total_amount = net_amount + tax_amount),
  CONSTRAINT ck_invoices_due CHECK (due_date >= issue_date)
);
CREATE INDEX ix_invoices_period ON invoices (period_month, direction);
```

### expenses
Excluyen nóminas y comisiones (la categoría `PAYROLL` no existe).
```sql
CREATE TABLE expenses (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  expense_number varchar(16) NOT NULL UNIQUE,
  expense_date   date NOT NULL,
  category       varchar(24) NOT NULL
                 CHECK (category IN ('RENT','SOFTWARE','MARKETING','PROFESSIONAL_SERVICES','UTILITIES','SUPPLIES')),
  supplier       varchar(120) NOT NULL,
  description    varchar(200) NOT NULL,
  amount         numeric(14,2) NOT NULL CHECK (amount >= 0),
  tax_amount     numeric(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
  total_amount   numeric(14,2) NOT NULL,
  currency       char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status         varchar(8) NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('DRAFT','APPROVED','VOID')),
  ledger_account varchar(8) NOT NULL,                      -- enlace contable por columna
  invoice_id     uuid REFERENCES invoices(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_expenses_total CHECK (total_amount = amount + tax_amount),
  CONSTRAINT ck_expenses_ledger CHECK (status = 'DRAFT' OR ledger_account <> '')
);
CREATE INDEX ix_expenses_date ON expenses (expense_date);
CREATE INDEX ix_expenses_invoice ON expenses (invoice_id);
```

### sales_targets
```sql
CREATE TABLE sales_targets (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id   uuid NOT NULL REFERENCES users(id),
  period_month  date NOT NULL CHECK (period_month = date_trunc('month', period_month)::date),
  target_amount numeric(14,2) NOT NULL CHECK (target_amount > 0),   -- objetivo de primas
  currency      char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  CONSTRAINT uq_sales_targets UNIQUE (employee_id, period_month)
);
```

---
## audit

### audit_logs
Solo metadatos; sin FK (sobrevive a cualquier limpieza). Sin particionado. Append-only por convención del repositorio (la aplicación solo inserta; en producción se revocaría `UPDATE/DELETE` al rol de la app).
```sql
CREATE TABLE audit_logs (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at    timestamptz NOT NULL DEFAULT now(),   -- tiempo real
  business_date  date NOT NULL,                        -- fecha ficticia del Clock
  user_id        uuid,                                 -- sin FK
  action         varchar(24) NOT NULL
                 CHECK (action IN ('LOGIN','LOGIN_FAILED','DOCUMENT_READ','DOCUMENT_DOWNLOAD','DOCUMENT_UPLOAD',
                                   'ACCESS_DENIED','CHAT','TOOL_CALL','FRAGMENT_DROPPED','IGNORED_CLIENT_FIELD')),
  resource_type  varchar(16),
  resource_id    varchar(64),
  decision       varchar(5) NOT NULL CHECK (decision IN ('ALLOW','DENY')),
  reason_code    varchar(40),
  channel        varchar(8) CHECK (channel IN ('REST','AI_TOOL','RAG')),
  correlation_id varchar(36),
  details        jsonb NOT NULL DEFAULT '{}'::jsonb    -- contadores y nombres, nunca contenido
);
CREATE INDEX ix_audit_logs_user_time ON audit_logs (user_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_action_time ON audit_logs (action, occurred_at DESC);
```

---
## Números ancla de octubre de 2026
Con los datos sembrados (consultas por `sale_date`, `period_month` y `expense_date`; `kind = 'ACCRUAL'` para comisiones):

| Magnitud | Valor |
|---|---|
| Pólizas vendidas en octubre | 12 (3 por empleado) |
| Primas | 9.680,00 € (EMP001 3.150, EMP002 2.280, EMP003 2.050, EMP004 2.200) |
| Comisiones de empleado (10 %) | 968,00 € |
| Gastos (`total_amount`, sin nóminas ni comisiones) | 7.250,00 € |
| Objetivos | 12.000,00 € (3.000 / 3.000 / 2.800 / 3.200); cumplimiento 105,0 / 76,0 / 73,2 / 68,8 % y 80,7 % total |

Validados con PostgreSQL 16 (ver [DECISIONES](../DECISIONES.md#validaciones)).
