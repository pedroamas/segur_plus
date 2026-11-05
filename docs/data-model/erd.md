# ERD de SegurPlus

Generado a partir de [schema.md](schema.md) (11 tablas) con un script auxiliar no versionado; si cambia schema.md hay que regenerarlo o actualizarlo a mano.
Notación Mermaid: `||` exactamente uno, `|o` cero o uno, `}o` cero o muchos. `audit_logs.user_id` es una referencia lógica sin FK (punteada).

## Vista general (solo relaciones)

```mermaid
erDiagram
  users |o--o{ documents : "owner_id"
  users ||--o{ documents : "created_by"
  users ||--o{ conversations : "user_id"
  conversations ||--o{ messages : "conversation_id"
  users ||--o{ policies : "employee_id"
  customers ||--o{ policies : "customer_id"
  users ||--o{ commissions : "employee_id"
  policies ||--o{ commissions : "policy_id"
  invoices |o--o{ expenses : "invoice_id"
  users ||--o{ sales_targets : "employee_id"
  users |o..o{ audit_logs : "user_id sin FK"
```

## Usuarios, documentos y conversaciones

```mermaid
erDiagram
  users {
    uuid id PK
    varchar employee_code UK
    varchar email UK
    varchar password_hash
    varchar full_name
    varchar role
    varchar department
    numeric monthly_salary
    char currency
    date hire_date
    varchar dify_user_ref UK
    boolean active
    timestamptz created_at
  }
  documents {
    uuid id PK
    varchar code UK
    varchar title
    varchar file_type
    varchar category
    varchar kb
    varchar classification
    uuid owner_id FK
    text_arr allowed_roles
    varchar storage_key UK
    char checksum_sha256
    bigint size_bytes
    varchar index_status
    varchar dify_dataset_id
    varchar dify_document_id
    timestamptz indexed_at
    uuid created_by FK
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  conversations {
    uuid id PK
    uuid user_id FK
    varchar title
    varchar dify_conversation_id UK
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  messages {
    uuid id PK
    uuid conversation_id FK
    varchar role
    text content
    jsonb sources
    jsonb tools
    integer tokens_in
    integer tokens_out
    integer latency_ms
    varchar status
    timestamptz created_at
  }
  users |o--o{ documents : "owner_id"
  users ||--o{ documents : "created_by"
  users ||--o{ conversations : "user_id"
  conversations ||--o{ messages : "conversation_id"
```

## Datos de negocio

```mermaid
erDiagram
  users {
    uuid id PK
    varchar employee_code UK
    varchar email UK
    varchar password_hash
    varchar full_name
    varchar role
    varchar department
    numeric monthly_salary
    char currency
    date hire_date
    varchar dify_user_ref UK
    boolean active
    timestamptz created_at
  }
  customers {
    uuid id PK
    varchar customer_number UK
    varchar full_name
    varchar email
    varchar city
    timestamptz created_at
  }
  policies {
    uuid id PK
    varchar policy_number UK
    uuid employee_id FK
    uuid customer_id FK
    varchar product
    varchar insurer
    numeric premium
    char currency
    varchar status
    date sale_date
    date start_date
    date renewal_date
    date cancelled_at
    numeric insurer_commission_rate
    numeric insurer_commission_amount
    timestamptz created_at
  }
  commissions {
    uuid id PK
    uuid employee_id FK
    uuid policy_id FK
    varchar kind
    date period_month
    numeric base_amount
    numeric rate
    numeric amount
    char currency
    timestamptz created_at
  }
  invoices {
    uuid id PK
    varchar invoice_number UK
    varchar direction
    varchar counterparty
    date issue_date
    date due_date
    date period_month
    numeric net_amount
    numeric tax_amount
    numeric total_amount
    char currency
    varchar status
    varchar ledger_account
    timestamptz created_at
  }
  expenses {
    uuid id PK
    varchar expense_number UK
    date expense_date
    varchar category
    varchar supplier
    varchar description
    numeric amount
    numeric tax_amount
    numeric total_amount
    char currency
    varchar status
    varchar ledger_account
    uuid invoice_id FK
    timestamptz created_at
  }
  sales_targets {
    uuid id PK
    uuid employee_id FK
    date period_month
    numeric target_amount
    char currency
  }
  users ||--o{ policies : "employee_id"
  customers ||--o{ policies : "customer_id"
  users ||--o{ commissions : "employee_id"
  policies ||--o{ commissions : "policy_id"
  invoices |o--o{ expenses : "invoice_id"
  users ||--o{ sales_targets : "employee_id"
```

## Auditoría

```mermaid
erDiagram
  audit_logs {
    bigint id PK
    timestamptz occurred_at
    date business_date
    uuid user_id
    varchar action
    varchar resource_type
    varchar resource_id
    varchar decision
    varchar reason_code
    varchar channel
    varchar correlation_id
    jsonb details
  }
```

