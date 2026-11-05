# ERD de SegurPlus

Generado a partir de [schema.md](schema.md) (54 tablas). Generado con un script auxiliar de F0 (no versionado); si cambia schema.md hay que regenerarlo o actualizarlo a mano.
Notación Mermaid: `||` exactamente uno, `|o` cero o uno, `}o` cero o muchos; línea continua = FK real, punteada = referencia lógica sin FK (polimórfica o de auditoría).
Las entidades marcadas solo con `id` en un diagrama de dominio son tablas de otro dominio (referencia).

## Vista general (solo relaciones)

```mermaid
erDiagram
  document_classifications ||--o{ roles : "clearance_level"
  users ||--o{ user_roles : "user_id"
  roles ||--o{ user_roles : "role_id"
  users |o--o{ user_roles : "granted_by_user_id"
  roles ||--o{ role_permissions : "role_id"
  permissions ||--o{ role_permissions : "permission_id"
  users ||--o{ refresh_tokens : "user_id"
  refresh_tokens |o--o{ refresh_tokens : "replaced_by_id"
  departments |o--o{ departments : "parent_department_id"
  employees |o--o{ departments : "head_employee_id"
  users |o--o| employees : "user_id"
  departments ||--o{ employees : "department_id"
  employees |o--o{ employees : "manager_employee_id"
  employees |o--o| knowledge_bases : "owner_employee_id"
  document_classifications ||--o{ knowledge_bases : "default_classification"
  document_categories ||--o{ documents : "category_id"
  document_classifications ||--o{ documents : "classification"
  employees |o--o{ documents : "owner_employee_id"
  users ||--o{ documents : "created_by_user_id"
  document_versions |o--o{ documents : "current_version_id"
  document_versions |o--o{ documents : "indexed_version_id"
  users |o--o{ documents : "deleted_by_user_id"
  knowledge_bases ||--o{ documents : "knowledge_base_id,dify_dataset_id"
  documents ||--o{ document_versions : "document_id"
  users ||--o{ document_versions : "created_by_user_id"
  documents ||--o{ document_permissions : "document_id"
  users ||--o{ document_permissions : "granted_by_user_id"
  users |o--o{ document_permissions : "revoked_by_user_id"
  permissions |o--o{ ai_tools : "required_permission_id"
  roles ||--o| ai_assistants : "role_id"
  ai_assistants ||--o{ ai_assistant_knowledge_bases : "assistant_id"
  knowledge_bases |o--o{ ai_assistant_knowledge_bases : "knowledge_base_id"
  document_categories |o--o{ ai_assistant_knowledge_bases : "category_id"
  ai_assistants ||--o{ ai_assistant_tools : "assistant_id"
  ai_tools ||--o{ ai_assistant_tools : "tool_code"
  users ||--o{ conversations : "user_id"
  ai_assistants ||--o{ conversations : "assistant_id"
  conversations ||--o{ messages : "conversation_id"
  messages ||--o{ message_sources : "message_id"
  documents ||--o{ message_sources : "document_id"
  document_versions ||--o{ message_sources : "document_version_id"
  messages ||--o{ message_tool_calls : "message_id"
  ai_tools ||--o{ message_tool_calls : "tool_code"
  users ||--o{ ai_evaluation_cases : "persona_user_id"
  users |o--o{ ai_evaluation_runs : "started_by_user_id"
  ai_evaluation_runs ||--o{ ai_evaluations : "run_id"
  ai_evaluation_cases ||--o{ ai_evaluations : "case_id"
  messages |o--o{ ai_evaluations : "message_id"
  employees |o--o{ customers : "assigned_employee_id"
  insurance_companies ||--o{ insurance_products : "insurance_company_id"
  sales ||--o| policies : "sale_id"
  customers ||--o{ policies : "customer_id"
  insurance_products ||--o{ policies : "insurance_product_id"
  policies ||--o{ policy_coverages : "policy_id"
  policies ||--o{ policy_renewals : "policy_id"
  employees |o--o{ policy_renewals : "handled_by_employee_id"
  policies ||--o| policy_cancellations : "policy_id"
  users ||--o{ policy_cancellations : "created_by_user_id"
  customers |o--o{ leads : "customer_id"
  employees ||--o{ leads : "assigned_employee_id"
  leads |o--o{ opportunities : "lead_id"
  customers ||--o{ opportunities : "customer_id"
  employees ||--o{ opportunities : "employee_id"
  insurance_products ||--o{ opportunities : "insurance_product_id"
  opportunities |o--o| sales : "opportunity_id"
  customers ||--o{ sales : "customer_id"
  employees ||--o{ sales : "employee_id"
  employees ||--o{ sales_activities : "employee_id"
  customers |o--o{ sales_activities : "customer_id"
  leads |o--o{ sales_activities : "lead_id"
  opportunities |o--o{ sales_activities : "opportunity_id"
  accounting_entries |o--o{ accounting_entries : "reversal_of_entry_id"
  users |o--o{ accounting_entries : "created_by_user_id"
  accounting_entries ||--o{ accounting_entry_lines : "entry_id"
  accounting_accounts ||--o{ accounting_entry_lines : "account_id"
  accounting_accounts ||--o{ expense_categories : "accounting_account_id"
  insurance_companies |o--o{ invoices : "insurance_company_id"
  accounting_entries |o--o{ invoices : "accounting_entry_id"
  policies ||--o| insurer_commissions : "policy_id"
  sales ||--o{ insurer_commissions : "sale_id"
  policy_renewals |o--o| insurer_commissions : "renewal_id"
  insurance_companies ||--o{ insurer_commissions : "insurance_company_id"
  invoices |o--o{ insurer_commissions : "invoice_id"
  insurer_commissions |o--o{ insurer_commissions : "clawback_of_id"
  employees ||--o{ commissions : "employee_id"
  sales ||--o| commissions : "sale_id"
  payments |o--o{ commissions : "payment_id"
  accounting_entries |o--o{ commissions : "accounting_entry_id"
  policy_cancellations |o--o{ commissions : "cancellation_id"
  invoices |o--o{ payments : "invoice_id"
  payroll_runs |o--o{ payments : "payroll_run_id"
  accounting_entries |o--o{ payments : "accounting_entry_id"
  expense_categories ||--o{ expenses : "category_id"
  invoices |o--o{ expenses : "invoice_id"
  accounting_entries |o--o{ expenses : "accounting_entry_id"
  employees |o--o{ expenses : "submitted_by_employee_id"
  employees |o--o{ expenses : "approved_by_employee_id"
  employees ||--o{ employment_contracts : "employee_id"
  documents |o--o{ employment_contracts : "document_id"
  accounting_entries |o--o{ payroll_runs : "accounting_entry_id"
  users |o--o{ payroll_runs : "approved_by_user_id"
  payroll_runs ||--o{ payroll : "payroll_run_id"
  employees ||--o{ payroll : "employee_id"
  employment_contracts ||--o{ payroll : "contract_id"
  employees ||--o{ sales_targets : "employee_id"
  users |o--o{ sales_targets : "set_by_user_id"
  employees ||--o{ performance_reviews : "employee_id"
  employees ||--o{ performance_reviews : "reviewer_employee_id"
  employees ||--o{ leave_requests : "employee_id"
  employees |o--o{ leave_requests : "reviewer_employee_id"
  users ||..o{ document_permissions : "subject_id si subject_type=USER"
  roles ||..o{ document_permissions : "subject_id si subject_type=ROLE"
  departments ||..o{ document_permissions : "subject_id si subject_type=DEPARTMENT"
  users |o..o{ audit_logs : "actor_user_id sin FK"
```

## Identidad, organización y autenticación

```mermaid
erDiagram
  users {
    uuid id PK
    varchar username
    varchar email
    varchar password_hash
    varchar status
    smallint failed_login_count
    timestamptz locked_until
    timestamptz last_login_at
    integer token_version
    varchar dify_user_ref UK
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  roles {
    uuid id PK
    varchar code UK
    varchar name
    text description
    varchar clearance_level FK
    timestamptz created_at
    timestamptz updated_at
  }
  permissions {
    uuid id PK
    varchar resource_type
    varchar action
    varchar scope
    text description
  }
  user_roles {
    uuid user_id PK, FK
    uuid role_id PK, FK
    timestamptz granted_at
    uuid granted_by_user_id FK
  }
  role_permissions {
    uuid role_id PK, FK
    uuid permission_id PK, FK
  }
  refresh_tokens {
    uuid id PK
    uuid user_id FK
    uuid family_id
    char token_hash UK
    timestamptz issued_at
    timestamptz expires_at
    timestamptz revoked_at
    uuid replaced_by_id FK
  }
  departments {
    uuid id PK
    varchar code
    varchar name
    uuid parent_department_id FK
    uuid head_employee_id FK
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  employees {
    uuid id PK
    varchar employee_code
    uuid user_id FK
    uuid department_id FK
    uuid manager_employee_id FK
    varchar first_name
    varchar last_name
    varchar job_title
    date hire_date
    date termination_date
    varchar status
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  document_classifications {
    varchar code PK
  }
  document_classifications ||--o{ roles : "clearance_level"
  users ||--o{ user_roles : "user_id"
  roles ||--o{ user_roles : "role_id"
  users |o--o{ user_roles : "granted_by_user_id"
  roles ||--o{ role_permissions : "role_id"
  permissions ||--o{ role_permissions : "permission_id"
  users ||--o{ refresh_tokens : "user_id"
  refresh_tokens |o--o{ refresh_tokens : "replaced_by_id"
  departments |o--o{ departments : "parent_department_id"
  employees |o--o{ departments : "head_employee_id"
  users |o--o| employees : "user_id"
  departments ||--o{ employees : "department_id"
  employees |o--o{ employees : "manager_employee_id"
```

## Gestión documental

```mermaid
erDiagram
  document_classifications {
    varchar code PK
    smallint rank UK
    text description
  }
  document_categories {
    uuid id PK
    varchar code UK
    varchar name
    text description
  }
  knowledge_bases {
    uuid id PK
    varchar code UK
    varchar name
    text description
    varchar dify_dataset_id UK
    uuid owner_employee_id FK
    varchar default_classification FK
    varchar status
    timestamptz created_at
    timestamptz updated_at
  }
  documents {
    uuid id PK
    varchar code
    varchar title
    text description
    uuid knowledge_base_id FK
    varchar dify_dataset_id FK
    uuid category_id FK
    varchar classification FK
    uuid owner_employee_id FK
    uuid created_by_user_id FK
    uuid current_version_id FK
    uuid indexed_version_id FK
    varchar mime_type
    bigint size_bytes
    char checksum_sha256
    varchar dify_document_id
    varchar index_status
    integer index_attempts
    varchar index_error_code
    timestamptz indexed_at
    boolean dify_metadata_synced
    bigint version
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
    uuid deleted_by_user_id FK
  }
  document_versions {
    uuid id PK
    uuid document_id FK
    integer version_number
    varchar storage_bucket
    varchar storage_key
    varchar original_filename
    varchar mime_type
    bigint size_bytes
    char checksum_sha256
    varchar scan_status
    timestamptz scanned_at
    varchar change_note
    uuid created_by_user_id FK
    timestamptz created_at
    timestamptz purged_at
  }
  document_permissions {
    uuid id PK
    uuid document_id FK
    varchar subject_type
    uuid subject_id
    varchar action
    timestamptz expires_at
    uuid granted_by_user_id FK
    varchar reason
    timestamptz created_at
    timestamptz revoked_at
    uuid revoked_by_user_id FK
  }
  outbox_events {
    uuid id PK
    varchar aggregate_type
    uuid aggregate_id
    varchar event_type
    jsonb payload
    varchar status
    integer attempts
    timestamptz next_attempt_at
    varchar last_error_code
    timestamptz created_at
    timestamptz processed_at
  }
  departments {
    uuid id PK
  }
  employees {
    uuid id PK
  }
  roles {
    uuid id PK
  }
  users {
    uuid id PK
  }
  employees |o--o| knowledge_bases : "owner_employee_id"
  document_classifications ||--o{ knowledge_bases : "default_classification"
  document_categories ||--o{ documents : "category_id"
  document_classifications ||--o{ documents : "classification"
  employees |o--o{ documents : "owner_employee_id"
  users ||--o{ documents : "created_by_user_id"
  document_versions |o--o{ documents : "current_version_id"
  document_versions |o--o{ documents : "indexed_version_id"
  users |o--o{ documents : "deleted_by_user_id"
  knowledge_bases ||--o{ documents : "knowledge_base_id,dify_dataset_id"
  documents ||--o{ document_versions : "document_id"
  users ||--o{ document_versions : "created_by_user_id"
  documents ||--o{ document_permissions : "document_id"
  users ||--o{ document_permissions : "granted_by_user_id"
  users |o--o{ document_permissions : "revoked_by_user_id"
  users ||..o{ document_permissions : "subject_id si subject_type=USER"
  roles ||..o{ document_permissions : "subject_id si subject_type=ROLE"
  departments ||..o{ document_permissions : "subject_id si subject_type=DEPARTMENT"
```

## IA: asistentes, conversaciones y evaluación

```mermaid
erDiagram
  ai_tools {
    varchar code PK
    varchar kind
    varchar description
    uuid required_permission_id FK
    integer schema_version
    boolean enabled
    timestamptz created_at
    timestamptz updated_at
  }
  ai_assistants {
    uuid id PK
    varchar code UK
    varchar type
    varchar name
    uuid role_id FK, UK
    text prompt_template
    integer prompt_version
    varchar model
    varchar fallback_model
    varchar dify_app_alias
    integer daily_token_quota
    smallint max_tool_calls
    jsonb params
    varchar status
    timestamptz created_at
    timestamptz updated_at
  }
  ai_assistant_knowledge_bases {
    uuid id PK
    uuid assistant_id FK
    uuid knowledge_base_id FK
    varchar kb_selector
    uuid category_id FK
  }
  ai_assistant_tools {
    uuid assistant_id PK, FK
    varchar tool_code PK, FK
  }
  conversations {
    uuid id PK
    uuid user_id FK
    uuid assistant_id FK
    varchar title
    varchar dify_conversation_id
    date business_date
    varchar status
    timestamptz last_message_at
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  messages {
    uuid id PK
    uuid conversation_id FK
    integer seq
    varchar role
    text content
    varchar status
    varchar route
    varchar model
    boolean fallback_used
    integer prompt_version
    integer input_tokens
    integer output_tokens
    numeric price_amount
    char price_currency
    integer latency_ms
    integer first_token_ms
    varchar error_code
    varchar dify_message_id
    date business_date
    timestamptz created_at
  }
  message_sources {
    uuid id PK
    uuid message_id FK
    uuid document_id FK
    uuid document_version_id FK
    smallint position
    varchar dify_segment_id
    numeric score
    integer page_number
  }
  message_tool_calls {
    uuid id PK
    uuid message_id FK
    smallint seq
    varchar tool_code FK
    jsonb arguments
    varchar status
    integer row_count
    integer duration_ms
    varchar error_code
    timestamptz created_at
  }
  ai_model_prices {
    uuid id PK
    varchar model
    numeric input_price_per_mtok
    numeric output_price_per_mtok
    char currency
    date valid_from
    date valid_to
  }
  ai_evaluation_cases {
    uuid id PK
    varchar case_code UK
    varchar set_name
    uuid persona_user_id FK
    text question
    varchar expected_route
    text[] expected_document_codes
    jsonb expected_facts
    boolean must_deny
    text[] tags
    timestamptz created_at
  }
  ai_evaluation_runs {
    uuid id PK
    timestamptz started_at
    timestamptz finished_at
    varchar status
    varchar git_sha
    jsonb config
    jsonb metrics
    uuid started_by_user_id FK
  }
  ai_evaluations {
    uuid id PK
    uuid run_id FK
    uuid case_id FK
    uuid message_id FK
    varchar actual_route
    boolean route_correct
    numeric recall_at_5
    numeric faithfulness
    boolean citations_valid
    boolean figures_verified
    boolean leaked_forbidden
    boolean passed
    varchar notes
    timestamptz created_at
  }
  document_categories {
    uuid id PK
  }
  document_versions {
    uuid id PK
  }
  documents {
    uuid id PK
  }
  knowledge_bases {
    uuid id PK
  }
  permissions {
    uuid id PK
  }
  roles {
    uuid id PK
  }
  users {
    uuid id PK
  }
  permissions |o--o{ ai_tools : "required_permission_id"
  roles ||--o| ai_assistants : "role_id"
  ai_assistants ||--o{ ai_assistant_knowledge_bases : "assistant_id"
  knowledge_bases |o--o{ ai_assistant_knowledge_bases : "knowledge_base_id"
  document_categories |o--o{ ai_assistant_knowledge_bases : "category_id"
  ai_assistants ||--o{ ai_assistant_tools : "assistant_id"
  ai_tools ||--o{ ai_assistant_tools : "tool_code"
  users ||--o{ conversations : "user_id"
  ai_assistants ||--o{ conversations : "assistant_id"
  conversations ||--o{ messages : "conversation_id"
  messages ||--o{ message_sources : "message_id"
  documents ||--o{ message_sources : "document_id"
  document_versions ||--o{ message_sources : "document_version_id"
  messages ||--o{ message_tool_calls : "message_id"
  ai_tools ||--o{ message_tool_calls : "tool_code"
  users ||--o{ ai_evaluation_cases : "persona_user_id"
  users |o--o{ ai_evaluation_runs : "started_by_user_id"
  ai_evaluation_runs ||--o{ ai_evaluations : "run_id"
  ai_evaluation_cases ||--o{ ai_evaluations : "case_id"
  messages |o--o{ ai_evaluations : "message_id"
```

## CRM y ventas

```mermaid
erDiagram
  customers {
    uuid id PK
    varchar customer_number
    varchar customer_type
    varchar first_name
    varchar last_name
    varchar company_name
    varchar tax_id
    varchar email
    varchar phone
    date birth_date
    varchar address_line
    varchar city
    varchar postal_code
    char country
    uuid assigned_employee_id FK
    boolean marketing_consent
    varchar acquisition_channel
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
    timestamptz anonymized_at
  }
  leads {
    uuid id PK
    uuid customer_id FK
    varchar contact_name
    varchar contact_email
    varchar contact_phone
    varchar source_channel
    varchar product_line
    varchar status
    uuid assigned_employee_id FK
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  opportunities {
    uuid id PK
    uuid lead_id FK
    uuid customer_id FK
    uuid employee_id FK
    uuid insurance_product_id FK
    varchar stage
    numeric expected_premium
    char currency
    numeric probability
    date expected_close_date
    timestamptz closed_at
    varchar lost_reason
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  sales {
    uuid id PK
    varchar sale_number UK
    uuid opportunity_id FK, UK
    uuid customer_id FK
    uuid employee_id FK
    date sale_date
    numeric premium_amount
    char currency
    varchar status
    timestamptz created_at
    timestamptz updated_at
  }
  sales_activities {
    uuid id PK
    uuid employee_id FK
    uuid customer_id FK
    uuid lead_id FK
    uuid opportunity_id FK
    varchar activity_type
    timestamptz occurred_at
    smallint duration_min
    varchar outcome
    timestamptz created_at
  }
  employees {
    uuid id PK
  }
  insurance_products {
    uuid id PK
  }
  employees |o--o{ customers : "assigned_employee_id"
  customers |o--o{ leads : "customer_id"
  employees ||--o{ leads : "assigned_employee_id"
  leads |o--o{ opportunities : "lead_id"
  customers ||--o{ opportunities : "customer_id"
  employees ||--o{ opportunities : "employee_id"
  insurance_products ||--o{ opportunities : "insurance_product_id"
  opportunities |o--o| sales : "opportunity_id"
  customers ||--o{ sales : "customer_id"
  employees ||--o{ sales : "employee_id"
  employees ||--o{ sales_activities : "employee_id"
  customers |o--o{ sales_activities : "customer_id"
  leads |o--o{ sales_activities : "lead_id"
  opportunities |o--o{ sales_activities : "opportunity_id"
```

## Seguros (corredor)

```mermaid
erDiagram
  insurance_companies {
    uuid id PK
    varchar code
    varchar name
    varchar tax_id
    varchar contact_email
    date agreement_start
    date agreement_end
    boolean active
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  insurance_products {
    uuid id PK
    uuid insurance_company_id FK
    varchar line
    varchar code
    varchar name
    text description
    numeric insurer_commission_rate
    boolean active
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at
  }
  policies {
    uuid id PK
    varchar policy_number UK
    uuid sale_id FK, UK
    uuid customer_id FK
    uuid insurance_product_id FK
    varchar status
    date start_date
    date end_date
    numeric annual_premium
    char currency
    varchar payment_frequency
    jsonb risk_data
    bigint version
    timestamptz created_at
    timestamptz updated_at
  }
  policy_coverages {
    uuid id PK
    uuid policy_id FK
    varchar coverage_code
    varchar name
    numeric insured_limit
    numeric deductible
    numeric premium_share
    char currency
    timestamptz created_at
  }
  policy_renewals {
    uuid id PK
    uuid policy_id FK
    date due_date
    varchar status
    numeric previous_premium
    numeric new_premium
    char currency
    date renewed_at
    date new_end_date
    uuid handled_by_employee_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  policy_cancellations {
    uuid id PK
    uuid policy_id FK, UK
    date requested_on
    date effective_date
    varchar reason
    varchar initiated_by
    numeric refunded_premium
    char currency
    boolean reverses_commission
    uuid created_by_user_id FK
    timestamptz created_at
  }
  customers {
    uuid id PK
  }
  employees {
    uuid id PK
  }
  sales {
    uuid id PK
  }
  users {
    uuid id PK
  }
  insurance_companies ||--o{ insurance_products : "insurance_company_id"
  sales ||--o| policies : "sale_id"
  customers ||--o{ policies : "customer_id"
  insurance_products ||--o{ policies : "insurance_product_id"
  policies ||--o{ policy_coverages : "policy_id"
  policies ||--o{ policy_renewals : "policy_id"
  employees |o--o{ policy_renewals : "handled_by_employee_id"
  policies ||--o| policy_cancellations : "policy_id"
  users ||--o{ policy_cancellations : "created_by_user_id"
```

## Finanzas

```mermaid
erDiagram
  accounting_accounts {
    uuid id PK
    varchar code UK
    varchar name
    varchar account_type
    boolean active
  }
  accounting_entries {
    uuid id PK
    bigint entry_number UK
    date entry_date
    varchar description
    varchar source_type
    uuid source_id
    varchar status
    uuid reversal_of_entry_id FK
    uuid created_by_user_id FK
    timestamptz created_at
  }
  accounting_entry_lines {
    uuid id PK
    uuid entry_id FK
    smallint line_no
    uuid account_id FK
    numeric debit
    numeric credit
    char currency
    varchar description
  }
  expense_categories {
    uuid id PK
    varchar code UK
    varchar name
    uuid accounting_account_id FK
    boolean active
  }
  expenses {
    uuid id PK
    varchar expense_number UK
    date expense_date
    uuid category_id FK
    varchar supplier_name
    varchar description
    numeric amount
    numeric tax_amount
    numeric total_amount
    char currency
    varchar status
    uuid invoice_id FK
    uuid accounting_entry_id FK
    uuid submitted_by_employee_id FK
    uuid approved_by_employee_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  invoices {
    uuid id PK
    varchar direction
    varchar invoice_number
    uuid insurance_company_id FK
    varchar supplier_name
    date issue_date
    date due_date
    date period_month
    numeric net_amount
    numeric tax_amount
    numeric total_amount
    char currency
    varchar status
    uuid accounting_entry_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  insurer_commissions {
    uuid id PK
    uuid policy_id FK
    uuid sale_id FK
    uuid renewal_id FK
    uuid insurance_company_id FK
    varchar kind
    numeric base_premium
    numeric insurer_commission_rate
    numeric amount
    char currency
    date accrual_date
    date period_month
    varchar status
    uuid invoice_id FK
    uuid clawback_of_id FK
    timestamptz created_at
  }
  commissions {
    uuid id PK
    uuid employee_id FK
    uuid sale_id FK, UK
    numeric base_amount
    numeric rate
    numeric amount
    char currency
    date period_month
    varchar status
    uuid payment_id FK
    uuid accounting_entry_id FK
    uuid cancellation_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  payments {
    uuid id PK
    varchar direction
    varchar payment_type
    uuid invoice_id FK
    uuid payroll_run_id FK
    numeric amount
    char currency
    date value_date
    varchar method
    varchar bank_reference
    varchar reconciliation_status
    timestamptz reconciled_at
    uuid accounting_entry_id FK
    timestamptz created_at
  }
  employees {
    uuid id PK
  }
  insurance_companies {
    uuid id PK
  }
  payroll_runs {
    uuid id PK
  }
  policies {
    uuid id PK
  }
  policy_cancellations {
    uuid id PK
  }
  policy_renewals {
    uuid id PK
  }
  sales {
    uuid id PK
  }
  users {
    uuid id PK
  }
  accounting_entries |o--o{ accounting_entries : "reversal_of_entry_id"
  users |o--o{ accounting_entries : "created_by_user_id"
  accounting_entries ||--o{ accounting_entry_lines : "entry_id"
  accounting_accounts ||--o{ accounting_entry_lines : "account_id"
  accounting_accounts ||--o{ expense_categories : "accounting_account_id"
  expense_categories ||--o{ expenses : "category_id"
  invoices |o--o{ expenses : "invoice_id"
  accounting_entries |o--o{ expenses : "accounting_entry_id"
  employees |o--o{ expenses : "submitted_by_employee_id"
  employees |o--o{ expenses : "approved_by_employee_id"
  insurance_companies |o--o{ invoices : "insurance_company_id"
  accounting_entries |o--o{ invoices : "accounting_entry_id"
  policies ||--o| insurer_commissions : "policy_id"
  sales ||--o{ insurer_commissions : "sale_id"
  policy_renewals |o--o| insurer_commissions : "renewal_id"
  insurance_companies ||--o{ insurer_commissions : "insurance_company_id"
  invoices |o--o{ insurer_commissions : "invoice_id"
  insurer_commissions |o--o{ insurer_commissions : "clawback_of_id"
  employees ||--o{ commissions : "employee_id"
  sales ||--o| commissions : "sale_id"
  payments |o--o{ commissions : "payment_id"
  accounting_entries |o--o{ commissions : "accounting_entry_id"
  policy_cancellations |o--o{ commissions : "cancellation_id"
  invoices |o--o{ payments : "invoice_id"
  payroll_runs |o--o{ payments : "payroll_run_id"
  accounting_entries |o--o{ payments : "accounting_entry_id"
```

## Recursos humanos

```mermaid
erDiagram
  employment_contracts {
    uuid id PK
    uuid employee_id FK
    varchar contract_type
    date start_date
    date end_date
    numeric base_salary_annual
    char currency
    numeric commission_rate
    numeric weekly_hours
    varchar status
    uuid document_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  payroll_runs {
    uuid id PK
    date period_month UK
    varchar status
    integer headcount
    numeric total_gross
    numeric total_commissions
    numeric total_net
    numeric total_employer_cost
    char currency
    uuid accounting_entry_id FK
    uuid approved_by_user_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  payroll {
    uuid id PK
    uuid payroll_run_id FK
    uuid employee_id FK
    uuid contract_id FK
    numeric base_salary
    numeric commissions_amount
    numeric other_earnings
    numeric gross_amount
    numeric social_security_employee
    numeric income_tax_withholding
    numeric net_amount
    numeric employer_social_security
    char currency
    timestamptz created_at
  }
  sales_targets {
    uuid id PK
    uuid employee_id FK
    date period_month
    numeric target_amount
    char currency
    uuid set_by_user_id FK
    timestamptz created_at
    timestamptz updated_at
  }
  performance_reviews {
    uuid id PK
    uuid employee_id FK
    uuid reviewer_employee_id FK
    date period_start
    date period_end
    smallint rating
    text summary
    varchar status
    timestamptz completed_at
    timestamptz created_at
    timestamptz updated_at
  }
  leave_requests {
    uuid id PK
    uuid employee_id FK
    varchar leave_type
    date start_date
    date end_date
    numeric days
    varchar status
    uuid reviewer_employee_id FK
    timestamptz decided_at
    varchar reason
    timestamptz created_at
    timestamptz updated_at
  }
  accounting_entries {
    uuid id PK
  }
  documents {
    uuid id PK
  }
  employees {
    uuid id PK
  }
  users {
    uuid id PK
  }
  employees ||--o{ employment_contracts : "employee_id"
  documents |o--o{ employment_contracts : "document_id"
  accounting_entries |o--o{ payroll_runs : "accounting_entry_id"
  users |o--o{ payroll_runs : "approved_by_user_id"
  payroll_runs ||--o{ payroll : "payroll_run_id"
  employees ||--o{ payroll : "employee_id"
  employment_contracts ||--o{ payroll : "contract_id"
  employees ||--o{ sales_targets : "employee_id"
  users |o--o{ sales_targets : "set_by_user_id"
  employees ||--o{ performance_reviews : "employee_id"
  employees ||--o{ performance_reviews : "reviewer_employee_id"
  employees ||--o{ leave_requests : "employee_id"
  employees |o--o{ leave_requests : "reviewer_employee_id"
```

## Auditoría

```mermaid
erDiagram
  audit_logs {
    bigint id PK
    timestamptz occurred_at PK
    date business_date
    uuid actor_user_id
    varchar actor_roles
    varchar event_type
    varchar outcome
    varchar channel
    varchar action
    varchar resource_type
    uuid resource_id
    uuid correlation_id
    char client_ip_hash
    jsonb details
  }
  users {
    uuid id PK
  }
  users |o..o{ audit_logs : "actor_user_id sin FK"
```

