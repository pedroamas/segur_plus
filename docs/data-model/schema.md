# Esquema PostgreSQL (documentación, no ejecutable)

El DDL es **documentación de diseño**; las migraciones Flyway reales se escriben en F1/F2. ERD: [erd.md](erd.md). 54 tablas, agrupadas por módulo propietario.

## Convenciones
| Tema | Regla |
|---|---|
| Claves | PK `uuid` con `gen_random_uuid()` (se puede generar UUIDv7 en la aplicación para localidad de índice). Excepciones: tablas de catálogo con clave natural (`document_classifications`, `ai_tools`), tablas puente (PK compuesta) y `audit_logs` (`bigint` identity + `occurred_at`). Los IDs expuestos por la API son estos UUID. |
| Importes | `NUMERIC(14,2)` + columna `currency char(3)` (ISO 4217, CHECK `^[A-Z]{3}$`, por defecto `EUR`). **Excepción propuesta** (H-12): precios y costes de IA por token usan `NUMERIC(14,6)`. |
| Tasas | `NUMERIC(5,4)` entre 0 y 1 (0,1000 = 10 %). |
| Tiempos | Instantes `timestamptz`. Fechas de negocio (venta, póliza, periodo) `date` en zona `Europe/Madrid`; los periodos mensuales son `date` con día 1 (CHECK). |
| Reloj | `created_at`/`updated_at` de tablas de **dominio** los pone la aplicación con el reloj de **negocio** (JPA auditing); en tablas **operativas y de seguridad** (`audit_logs`, `refresh_tokens`, `outbox_events`, `users.last_login_at`, `users.locked_until`) con el reloj de **sistema** (ADR 0006). El `DEFAULT now()` es solo red de seguridad. |
| Enumeraciones | `text`/`varchar` + `CHECK (col IN (...))` (más fácil de migrar que `ENUM`). |
| Borrado | Soft delete (`deleted_at`) en entidades maestras y documentos. **Sin** borrado lógico en registros contables y de auditoría (se anulan con estado `VOID`/contraasiento). Índices únicos de negocio son **parciales** (`WHERE deleted_at IS NULL`). |
| Concurrencia | `version bigint` (bloqueo optimista JPA) donde hay edición concurrente relevante. |
| FK | Toda FK lleva índice en la columna origen salvo que la cubra otro índice (se indica). `ON DELETE` por defecto `RESTRICT`. |
| Texto sensible | Notas libres no contienen datos médicos ni salariales. `messages.content` es la única tabla con contenido de conversación (H-13). |
| Auditoría | `audit_logs` guarda **solo metadatos**; sin FK a otras tablas para sobrevivir a anonimizaciones y retención. |

---
# Módulo `users`

### users
```sql
CREATE TABLE users (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username           varchar(64)  NOT NULL,
  email              varchar(254) NOT NULL,
  password_hash      varchar(100) NOT NULL,          -- Argon2id o bcrypt
  status             varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
  failed_login_count smallint     NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
  locked_until       timestamptz,
  last_login_at      timestamptz,
  token_version      integer      NOT NULL DEFAULT 0,  -- invalida JWT al subirla
  dify_user_ref      varchar(40)  NOT NULL,            -- 'u_<uuid>' opaco y estable, distinto de id
  created_at         timestamptz  NOT NULL DEFAULT now(),
  updated_at         timestamptz  NOT NULL DEFAULT now(),
  deleted_at         timestamptz,
  CONSTRAINT uq_users_dify_user_ref UNIQUE (dify_user_ref),
  CONSTRAINT ck_users_dify_user_ref CHECK (dify_user_ref ~ '^u_[0-9a-f-]{36}$')
);
CREATE UNIQUE INDEX uq_users_username_active ON users (lower(username)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_email_active    ON users (lower(email))    WHERE deleted_at IS NULL;
```

### roles
```sql
CREATE TABLE roles (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code            varchar(32) NOT NULL,                 -- MANAGER, ACCOUNTANT, SALES_MANAGER, EMPLOYEE
  name            varchar(100) NOT NULL,
  description     text,
  clearance_level varchar(16) NOT NULL REFERENCES document_classifications(code),  -- H-03
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_roles_code UNIQUE (code)
);
CREATE INDEX ix_roles_clearance ON roles (clearance_level);
```
Siembra: MANAGER→RESTRICTED, ACCOUNTANT→CONFIDENTIAL, SALES_MANAGER→CONFIDENTIAL, EMPLOYEE→INTERNAL.

### permissions
Permisos de **datos** (no documentales): qué acción sobre qué tipo de recurso con qué alcance. Los grants por documento van en `document_permissions`.
```sql
CREATE TABLE permissions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  resource_type varchar(32) NOT NULL CHECK (resource_type IN (
                'DOCUMENT','KNOWLEDGE_BASE','CONVERSATION','CUSTOMER','LEAD','OPPORTUNITY','SALE',
                'SALES_ACTIVITY','POLICY','COMMISSION','TARGET','EXPENSE','INVOICE','PAYMENT','LEDGER',
                'PAYROLL','CONTRACT','PERFORMANCE_REVIEW','LEAVE','USER','ASSISTANT','AUDIT_LOG','EVALUATION')),
  action        varchar(16) NOT NULL CHECK (action IN ('READ','CREATE','UPDATE','DELETE','DOWNLOAD','ADMINISTER')),
  scope         varchar(16) NOT NULL CHECK (scope IN ('OWN','TEAM','DEPARTMENT','GLOBAL','AGGREGATE')),
  description   text,
  CONSTRAINT uq_permissions UNIQUE (resource_type, action, scope)
);
```
`AGGREGATE` = solo agregados sin detalle individual (nóminas para `ACCOUNTANT`, A5).

### user_roles
```sql
CREATE TABLE user_roles (
  user_id            uuid NOT NULL REFERENCES users(id),
  role_id            uuid NOT NULL REFERENCES roles(id),
  granted_at         timestamptz NOT NULL DEFAULT now(),
  granted_by_user_id uuid REFERENCES users(id),
  PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id);
CREATE INDEX ix_user_roles_granted_by ON user_roles (granted_by_user_id) WHERE granted_by_user_id IS NOT NULL;
```
v1: un rol por usuario (H-21); la PK compuesta permite ampliarlo.

### role_permissions
```sql
CREATE TABLE role_permissions (
  role_id       uuid NOT NULL REFERENCES roles(id),
  permission_id uuid NOT NULL REFERENCES permissions(id),
  PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX ix_role_permissions_permission ON role_permissions (permission_id);
```

# Módulo `auth`

### refresh_tokens
```sql
CREATE TABLE refresh_tokens (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid        NOT NULL REFERENCES users(id),
  family_id      uuid        NOT NULL,               -- cadena de rotación; reutilización ⇒ revocar la familia
  token_hash     char(64)    NOT NULL,               -- SHA-256 del token; el token nunca se guarda
  issued_at      timestamptz NOT NULL DEFAULT now(), -- reloj de sistema
  expires_at     timestamptz NOT NULL,
  revoked_at     timestamptz,
  replaced_by_id uuid REFERENCES refresh_tokens(id),
  CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
  CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX ix_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens (expires_at);
CREATE INDEX ix_refresh_tokens_replaced_by ON refresh_tokens (replaced_by_id) WHERE replaced_by_id IS NOT NULL;
```

# Módulo `departments`

### departments
```sql
CREATE TABLE departments (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code               varchar(16)  NOT NULL,            -- DIR, CONT, COM
  name               varchar(100) NOT NULL,
  parent_department_id uuid REFERENCES departments(id),
  head_employee_id   uuid REFERENCES employees(id) DEFERRABLE INITIALLY DEFERRED,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  deleted_at         timestamptz
);
CREATE UNIQUE INDEX uq_departments_code_active ON departments (code) WHERE deleted_at IS NULL;
CREATE INDEX ix_departments_parent ON departments (parent_department_id) WHERE parent_department_id IS NOT NULL;
CREATE INDEX ix_departments_head ON departments (head_employee_id) WHERE head_employee_id IS NOT NULL;
```

### employees
```sql
CREATE TABLE employees (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_code       varchar(16)  NOT NULL,           -- DIR001, ACC001, COM001, EMP001..EMP004
  user_id             uuid REFERENCES users(id),       -- 1:1 con users
  department_id       uuid NOT NULL REFERENCES departments(id),
  manager_employee_id uuid REFERENCES employees(id),   -- define el «equipo» del responsable
  first_name          varchar(100) NOT NULL,
  last_name           varchar(150) NOT NULL,
  job_title           varchar(120) NOT NULL,
  hire_date           date NOT NULL,
  termination_date    date,
  status              varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ON_LEAVE','TERMINATED')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  deleted_at          timestamptz,
  CONSTRAINT ck_employees_dates CHECK (termination_date IS NULL OR termination_date >= hire_date),
  CONSTRAINT ck_employees_not_own_manager CHECK (manager_employee_id IS NULL OR manager_employee_id <> id)
);
CREATE UNIQUE INDEX uq_employees_code_active ON employees (employee_code) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_employees_user ON employees (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_employees_department ON employees (department_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_employees_manager ON employees (manager_employee_id) WHERE manager_employee_id IS NOT NULL;
```

# Módulo `documents`

### document_classifications
```sql
CREATE TABLE document_classifications (
  code        varchar(16) PRIMARY KEY CHECK (code IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
  rank        smallint NOT NULL,                       -- PUBLIC=1 < INTERNAL=2 < CONFIDENTIAL=3 < RESTRICTED=4
  description text,
  CONSTRAINT uq_document_classifications_rank UNIQUE (rank),
  CONSTRAINT ck_document_classifications_rank CHECK (rank BETWEEN 1 AND 4)
);
```

### document_categories
```sql
CREATE TABLE document_categories (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code        varchar(40)  NOT NULL,   -- MANAGEMENT_REPORT, STRATEGY, MINUTES, SALES_REPORT, INVOICES, LEDGER,
                                       -- BANK_STATEMENT, RECONCILIATION, COMMISSION_STATEMENT, EXPENSES, CLOSING_REPORT,
                                       -- CONTRACT, COMMERCIAL_ACTIVITY, PAYSLIP, INSURANCE_MANUAL, COMPANY_POLICY, KPI, BUDGET
  name        varchar(120) NOT NULL,
  description text,
  CONSTRAINT uq_document_categories_code UNIQUE (code)
);
```

### knowledge_bases
Las 9 KBs: `MANAGEMENT, SALES, FINANCE, INSURANCE, COMPANY, EMP001, EMP002, EMP003, EMP004`. Un dataset de Dify por KB.
```sql
CREATE TABLE knowledge_bases (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                   varchar(32)  NOT NULL,
  name                   varchar(120) NOT NULL,
  description            text,
  dify_dataset_id        varchar(64),                 -- NULL hasta aprovisionar; nunca llega al cliente
  owner_employee_id      uuid REFERENCES employees(id),  -- solo KB-EMP00X
  default_classification varchar(16) NOT NULL REFERENCES document_classifications(code),
  status                 varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_knowledge_bases_code UNIQUE (code),
  CONSTRAINT uq_knowledge_bases_dify_dataset UNIQUE (dify_dataset_id),
  CONSTRAINT uq_knowledge_bases_id_dataset UNIQUE (id, dify_dataset_id)   -- destino de la FK compuesta de documents
);
CREATE UNIQUE INDEX uq_knowledge_bases_owner ON knowledge_bases (owner_employee_id) WHERE owner_employee_id IS NOT NULL;
CREATE INDEX ix_knowledge_bases_default_classification ON knowledge_bases (default_classification);
```

### documents
```sql
CREATE TABLE documents (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                  varchar(16),                    -- D01..D29, G01..G03 (dataset); NULL en subidas ad hoc
  title                 varchar(255) NOT NULL,
  description           text,
  knowledge_base_id     uuid NOT NULL,
  dify_dataset_id       varchar(64) NOT NULL,           -- copia desnormalizada exigida por el diseño
  category_id           uuid NOT NULL REFERENCES document_categories(id),
  classification        varchar(16) NOT NULL REFERENCES document_classifications(code),
  owner_employee_id     uuid REFERENCES employees(id),  -- empleado a quien pertenecen los datos; NULL en corporativos
  created_by_user_id    uuid NOT NULL REFERENCES users(id),
  current_version_id    uuid REFERENCES document_versions(id) DEFERRABLE INITIALLY DEFERRED,
  indexed_version_id    uuid REFERENCES document_versions(id) DEFERRABLE INITIALLY DEFERRED,
  mime_type             varchar(127) NOT NULL,
  size_bytes            bigint CHECK (size_bytes > 0),
  checksum_sha256       char(64) CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),   -- copia del de current_version_id
  dify_document_id      varchar(64),
  index_status          varchar(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (index_status IN ('PENDING','INDEXING','INDEXED','FAILED')),
  index_attempts        integer NOT NULL DEFAULT 0 CHECK (index_attempts >= 0),
  index_error_code      varchar(64),                    -- código, nunca el contenido
  indexed_at            timestamptz,
  dify_metadata_synced  boolean NOT NULL DEFAULT false,
  version               bigint NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  deleted_at            timestamptz,
  deleted_by_user_id    uuid REFERENCES users(id),
  CONSTRAINT fk_documents_kb_dataset FOREIGN KEY (knowledge_base_id, dify_dataset_id)
    REFERENCES knowledge_bases (id, dify_dataset_id) MATCH FULL ON UPDATE CASCADE,
  CONSTRAINT ck_documents_indexed CHECK (index_status <> 'INDEXED'
    OR (dify_document_id IS NOT NULL AND indexed_version_id IS NOT NULL AND indexed_at IS NOT NULL)),
  CONSTRAINT ck_documents_deleted CHECK ((deleted_at IS NULL) = (deleted_by_user_id IS NULL))
);
CREATE UNIQUE INDEX uq_documents_code_active ON documents (code) WHERE code IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_documents_dify_document ON documents (dify_dataset_id, dify_document_id) WHERE dify_document_id IS NOT NULL;
CREATE INDEX ix_documents_kb ON documents (knowledge_base_id, dify_dataset_id);
CREATE INDEX ix_documents_owner ON documents (owner_employee_id) WHERE owner_employee_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_classification ON documents (classification) WHERE deleted_at IS NULL;
CREATE INDEX ix_documents_category ON documents (category_id);
CREATE INDEX ix_documents_created_by ON documents (created_by_user_id);
CREATE INDEX ix_documents_deleted_by ON documents (deleted_by_user_id) WHERE deleted_by_user_id IS NOT NULL;
CREATE INDEX ix_documents_pipeline ON documents (index_status, updated_at)
  WHERE deleted_at IS NULL AND index_status IN ('PENDING','INDEXING','FAILED');
CREATE INDEX ix_documents_meta_unsynced ON documents (updated_at)
  WHERE deleted_at IS NULL AND dify_metadata_synced = false;
CREATE INDEX ix_documents_current_version ON documents (current_version_id) WHERE current_version_id IS NOT NULL;
CREATE INDEX ix_documents_indexed_version ON documents (indexed_version_id) WHERE indexed_version_id IS NOT NULL;
CREATE INDEX ix_documents_deleted ON documents (deleted_at) WHERE deleted_at IS NOT NULL;   -- purga diferida
CREATE INDEX ix_documents_title_fts ON documents USING gin (to_tsvector('spanish', title));
```
Invariantes que no caben en CHECK (se prueban y/o usan trigger): `classification` y `owner_employee_id` coherentes con `knowledge_bases.owner_employee_id` en KB-EMP; `checksum_sha256`/`size_bytes` = los de `current_version_id`; el soft delete no borra grants (se conservan para auditoría).

### document_versions
```sql
CREATE TABLE document_versions (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id        uuid NOT NULL REFERENCES documents(id) DEFERRABLE INITIALLY DEFERRED,
  version_number     integer NOT NULL CHECK (version_number >= 1),
  storage_bucket     varchar(63)  NOT NULL,
  storage_key        varchar(512) NOT NULL,           -- documents/<docId>/v<n>/<sha256>
  original_filename  varchar(255) NOT NULL,
  mime_type          varchar(127) NOT NULL,            -- MIME real detectado, no el declarado
  size_bytes         bigint NOT NULL CHECK (size_bytes > 0),
  checksum_sha256    char(64) NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  scan_status        varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','ERROR')),
  scanned_at         timestamptz,
  change_note        varchar(500),
  created_by_user_id uuid NOT NULL REFERENCES users(id),
  created_at         timestamptz NOT NULL DEFAULT now(),
  purged_at          timestamptz,                      -- binario purgado de MinIO; la fila se conserva
  CONSTRAINT uq_document_versions_number UNIQUE (document_id, version_number),
  CONSTRAINT uq_document_versions_object UNIQUE (storage_bucket, storage_key)
);
CREATE INDEX ix_document_versions_checksum ON document_versions (checksum_sha256);
CREATE INDEX ix_document_versions_created_by ON document_versions (created_by_user_id);
CREATE INDEX ix_document_versions_scan_pending ON document_versions (created_at) WHERE scan_status = 'PENDING';
```
Las versiones son inmutables salvo `scan_*` y `purged_at`. La unicidad `(document_id, version_number)` ya cubre el índice por `document_id`.

### document_permissions
Grants **de concesión** (deny by default: no hay filas DENY). `CREATE` no se genera por documento (H-05).
```sql
CREATE TABLE document_permissions (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id        uuid NOT NULL REFERENCES documents(id),
  subject_type       varchar(16) NOT NULL CHECK (subject_type IN ('USER','ROLE','DEPARTMENT')),
  subject_id         uuid NOT NULL,                    -- users.id | roles.id | departments.id según subject_type
  action             varchar(16) NOT NULL CHECK (action IN ('READ','CREATE','UPDATE','DELETE','DOWNLOAD','ADMINISTER')),
  expires_at         timestamptz,                      -- NULL = no caduca
  granted_by_user_id uuid NOT NULL REFERENCES users(id),
  reason             varchar(300),
  created_at         timestamptz NOT NULL DEFAULT now(),
  revoked_at         timestamptz,
  revoked_by_user_id uuid REFERENCES users(id),
  CONSTRAINT ck_document_permissions_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
  CONSTRAINT ck_document_permissions_revoked CHECK ((revoked_at IS NULL) = (revoked_by_user_id IS NULL))
);
CREATE UNIQUE INDEX uq_document_permissions_active
  ON document_permissions (document_id, subject_type, subject_id, action) WHERE revoked_at IS NULL;
CREATE INDEX ix_document_permissions_subject
  ON document_permissions (subject_type, subject_id, action, document_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_document_permissions_expiry ON document_permissions (expires_at)
  WHERE revoked_at IS NULL AND expires_at IS NOT NULL;
CREATE INDEX ix_document_permissions_granted_by ON document_permissions (granted_by_user_id);
CREATE INDEX ix_document_permissions_revoked_by ON document_permissions (revoked_by_user_id) WHERE revoked_by_user_id IS NOT NULL;
```
`subject_id` es una referencia **polimórfica sin FK**. Mitigación: trigger de validación al insertar (el sujeto existe en la tabla que indica `subject_type`), borrado lógico de sujetos que revoca sus grants, y test de integridad. Alternativa descartada: tres columnas FK nulables (más rígidas para consultar «todos los sujetos»). La caducidad se evalúa en consulta con el reloj de negocio (`expires_at IS NULL OR expires_at > :now`), no en el índice.

# Módulo `ai`

### ai_tools
Catálogo de herramientas tipadas (ADR 0005). El esquema de parámetros vive en código; aquí solo el registro y el permiso que exige.
```sql
CREATE TABLE ai_tools (
  code                 varchar(64) PRIMARY KEY,         -- get_sales_summary, get_commissions, ...
  kind                 varchar(8)  NOT NULL CHECK (kind IN ('SQL','RAG')),
  description          varchar(500) NOT NULL,
  required_permission_id uuid REFERENCES permissions(id),
  schema_version       integer NOT NULL DEFAULT 1,
  enabled              boolean NOT NULL DEFAULT true,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_ai_tools_code CHECK (code ~ '^[a-z][a-z0-9_]{2,63}$')
);
CREATE INDEX ix_ai_tools_permission ON ai_tools (required_permission_id) WHERE required_permission_id IS NOT NULL;
```

### ai_assistants
Un asistente es un **perfil de configuración** (A6), no una app de Dify por usuario. Se resuelve por el rol del JWT.
```sql
CREATE TABLE ai_assistants (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code              varchar(32)  NOT NULL,
  type              varchar(16)  NOT NULL CHECK (type IN ('MANAGEMENT','ACCOUNTING','SALES','EMPLOYEE')),
  name              varchar(100) NOT NULL,
  role_id           uuid NOT NULL REFERENCES roles(id),  -- un asistente por rol en v1
  prompt_template   text NOT NULL,                       -- con variables {{today}}, {{user_display_name}}, ...
  prompt_version    integer NOT NULL DEFAULT 1 CHECK (prompt_version >= 1),
  model             varchar(64) NOT NULL,
  fallback_model    varchar(64),
  dify_app_alias    varchar(64) NOT NULL,                -- alias de la clave de app en el gestor de secretos, no la clave
  daily_token_quota integer CHECK (daily_token_quota > 0),
  max_tool_calls    smallint NOT NULL DEFAULT 4 CHECK (max_tool_calls BETWEEN 0 AND 10),
  params            jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(params) = 'object'),  -- temperature, top_k, score_threshold, max_tokens
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_ai_assistants_code UNIQUE (code),
  CONSTRAINT uq_ai_assistants_role UNIQUE (role_id)
);
```

### ai_assistant_knowledge_bases
KBs permitidas (cota superior, no concesión: el acceso real sigue siendo la política).
```sql
CREATE TABLE ai_assistant_knowledge_bases (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  assistant_id      uuid NOT NULL REFERENCES ai_assistants(id),
  knowledge_base_id uuid REFERENCES knowledge_bases(id),
  kb_selector       varchar(24) CHECK (kb_selector IN ('OWN_EMPLOYEE_KB','ALL_EMPLOYEE_KBS')),
  category_id       uuid REFERENCES document_categories(id),   -- restringe a una categoría (p. ej. COMMERCIAL_ACTIVITY)
  CONSTRAINT ck_ai_akb_target CHECK (num_nonnulls(knowledge_base_id, kb_selector) = 1)
);
CREATE UNIQUE INDEX uq_ai_akb_fixed ON ai_assistant_knowledge_bases (assistant_id, knowledge_base_id, category_id) NULLS NOT DISTINCT
  WHERE knowledge_base_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ai_akb_selector ON ai_assistant_knowledge_bases (assistant_id, kb_selector, category_id) NULLS NOT DISTINCT
  WHERE kb_selector IS NOT NULL;
CREATE INDEX ix_ai_akb_kb ON ai_assistant_knowledge_bases (knowledge_base_id) WHERE knowledge_base_id IS NOT NULL;
CREATE INDEX ix_ai_akb_category ON ai_assistant_knowledge_bases (category_id) WHERE category_id IS NOT NULL;
```

### ai_assistant_tools
```sql
CREATE TABLE ai_assistant_tools (
  assistant_id uuid NOT NULL REFERENCES ai_assistants(id),
  tool_code    varchar(64) NOT NULL REFERENCES ai_tools(code),
  PRIMARY KEY (assistant_id, tool_code)
);
CREATE INDEX ix_ai_assistant_tools_tool ON ai_assistant_tools (tool_code);
```

### conversations
```sql
CREATE TABLE conversations (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid NOT NULL REFERENCES users(id),        -- propietario; fuente de verdad (no Dify)
  assistant_id         uuid NOT NULL REFERENCES ai_assistants(id),
  title                varchar(200),
  dify_conversation_id varchar(64),                               -- generado por Dify
  business_date        date NOT NULL,                             -- fecha de negocio de inicio
  status               varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  last_message_at      timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  deleted_at           timestamptz
);
CREATE UNIQUE INDEX uq_conversations_dify ON conversations (dify_conversation_id) WHERE dify_conversation_id IS NOT NULL;
CREATE INDEX ix_conversations_owner_recent ON conversations (user_id, last_message_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_conversations_assistant ON conversations (assistant_id);
```

### messages
```sql
CREATE TABLE messages (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id  uuid NOT NULL REFERENCES conversations(id),
  seq              integer NOT NULL CHECK (seq >= 1),
  role             varchar(10) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
  content          text NOT NULL,                                  -- H-13: solo aquí, nunca en logs ni auditoría
  status           varchar(12) NOT NULL DEFAULT 'COMPLETED'
                   CHECK (status IN ('PENDING','STREAMING','COMPLETED','FAILED','CANCELLED','BLOCKED')),
  route            varchar(8) CHECK (route IN ('RAG','SQL','BOTH','NONE')),    -- decisión de routing (evaluación)
  model            varchar(64),
  fallback_used    boolean NOT NULL DEFAULT false,
  prompt_version   integer,
  input_tokens     integer CHECK (input_tokens >= 0),              -- suma de llamadas del turno
  output_tokens    integer CHECK (output_tokens >= 0),
  price_amount     numeric(14,6) CHECK (price_amount >= 0),        -- excepción H-12
  price_currency   char(3) CHECK (price_currency ~ '^[A-Z]{3}$'),
  latency_ms       integer CHECK (latency_ms >= 0),
  first_token_ms   integer CHECK (first_token_ms >= 0),
  error_code       varchar(64),
  dify_message_id  varchar(64),
  business_date    date NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_messages_seq UNIQUE (conversation_id, seq),
  CONSTRAINT ck_messages_usage_role CHECK (role = 'ASSISTANT'
    OR (input_tokens IS NULL AND output_tokens IS NULL AND price_amount IS NULL AND model IS NULL)),
  CONSTRAINT ck_messages_price_pair CHECK ((price_amount IS NULL) = (price_currency IS NULL))
);
CREATE INDEX ix_messages_usage_day ON messages (business_date, conversation_id) WHERE role = 'ASSISTANT';
CREATE INDEX ix_messages_status_open ON messages (created_at) WHERE status IN ('PENDING','STREAMING');
CREATE INDEX ix_messages_dify ON messages (dify_message_id) WHERE dify_message_id IS NOT NULL;
```
La cuota diaria por usuario = suma de `input_tokens + output_tokens` de `messages` ⨝ `conversations.user_id` en `business_date`.

### message_sources
```sql
CREATE TABLE message_sources (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id          uuid NOT NULL REFERENCES messages(id),
  document_id         uuid NOT NULL REFERENCES documents(id),
  document_version_id uuid NOT NULL REFERENCES document_versions(id),
  position            smallint NOT NULL CHECK (position >= 1),
  dify_segment_id     varchar(64),
  score               numeric(6,5) CHECK (score BETWEEN 0 AND 1),
  page_number         integer CHECK (page_number >= 1),
  CONSTRAINT uq_message_sources_pos UNIQUE (message_id, position)
);
CREATE INDEX ix_message_sources_document ON message_sources (document_id);
CREATE INDEX ix_message_sources_version ON message_sources (document_version_id);
```
Referencias, no texto del fragmento. Al leer el historial se re-autoriza `READ` sobre `document_id` y las fuentes no autorizadas se ocultan (H-13).

### message_tool_calls
```sql
CREATE TABLE message_tool_calls (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id  uuid NOT NULL REFERENCES messages(id),
  seq         smallint NOT NULL CHECK (seq >= 1),
  tool_code   varchar(64) NOT NULL REFERENCES ai_tools(code),
  arguments   jsonb NOT NULL CHECK (jsonb_typeof(arguments) = 'object'),   -- parámetros tipados; nunca employee_id
  status      varchar(12) NOT NULL CHECK (status IN ('OK','DENIED','ERROR','TIMEOUT')),
  row_count   integer CHECK (row_count >= 0),
  duration_ms integer CHECK (duration_ms >= 0),
  error_code  varchar(64),
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_message_tool_calls_seq UNIQUE (message_id, seq)
);
CREATE INDEX ix_message_tool_calls_tool ON message_tool_calls (tool_code, created_at);
```

### ai_model_prices
```sql
CREATE TABLE ai_model_prices (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  model               varchar(64) NOT NULL,
  input_price_per_mtok  numeric(14,6) NOT NULL CHECK (input_price_per_mtok >= 0),    -- por millón de tokens
  output_price_per_mtok numeric(14,6) NOT NULL CHECK (output_price_per_mtok >= 0),
  currency            char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  valid_from          date NOT NULL,
  valid_to            date,
  CONSTRAINT ck_ai_model_prices_range CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ex_ai_model_prices_overlap EXCLUDE USING gist
    (model WITH =, daterange(valid_from, valid_to, '[]') WITH &&)          -- requiere btree_gist
);
```
Las tarifas son datos de configuración, no hechos; hay que confirmarlas contra la tarifa vigente del proveedor al sembrar (no verificado en F0).

### ai_evaluation_cases
Golden set **separado del corpus** documental.
```sql
CREATE TABLE ai_evaluation_cases (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  case_code               varchar(32) NOT NULL,
  set_name                varchar(64) NOT NULL,              -- golden, routing, access-negative, injection
  persona_user_id         uuid NOT NULL REFERENCES users(id),  -- usuario que formula la pregunta
  question                text NOT NULL,
  expected_route          varchar(8) NOT NULL CHECK (expected_route IN ('RAG','SQL','BOTH','NONE')),
  expected_document_codes text[] NOT NULL DEFAULT '{}',        -- códigos D01..G03, no FK (el corpus es independiente)
  expected_facts          jsonb NOT NULL DEFAULT '{}'::jsonb,
  must_deny               boolean NOT NULL DEFAULT false,      -- caso negativo: no debe aparecer dato ajeno
  tags                    text[] NOT NULL DEFAULT '{}',
  created_at              timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_ai_evaluation_cases_code UNIQUE (case_code)
);
CREATE INDEX ix_ai_evaluation_cases_set ON ai_evaluation_cases (set_name);
CREATE INDEX ix_ai_evaluation_cases_persona ON ai_evaluation_cases (persona_user_id);
```

### ai_evaluation_runs
```sql
CREATE TABLE ai_evaluation_runs (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  started_at   timestamptz NOT NULL DEFAULT now(),
  finished_at  timestamptz,
  status       varchar(12) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  git_sha      varchar(40),
  config       jsonb NOT NULL DEFAULT '{}'::jsonb,              -- modelo, parámetros, versión de prompt, umbrales
  metrics      jsonb NOT NULL DEFAULT '{}'::jsonb,              -- recall@5, fidelidad, acierto de routing
  started_by_user_id uuid REFERENCES users(id),
  CONSTRAINT ck_ai_evaluation_runs_time CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX ix_ai_evaluation_runs_started ON ai_evaluation_runs (started_at DESC);
CREATE INDEX ix_ai_evaluation_runs_user ON ai_evaluation_runs (started_by_user_id) WHERE started_by_user_id IS NOT NULL;
```

### ai_evaluations
Resultado de un caso en una ejecución.
```sql
CREATE TABLE ai_evaluations (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id              uuid NOT NULL REFERENCES ai_evaluation_runs(id),
  case_id             uuid NOT NULL REFERENCES ai_evaluation_cases(id),
  message_id          uuid REFERENCES messages(id),             -- turno real ejecutado por el pipeline
  actual_route        varchar(8) CHECK (actual_route IN ('RAG','SQL','BOTH','NONE')),
  route_correct       boolean,
  recall_at_5         numeric(4,3) CHECK (recall_at_5 BETWEEN 0 AND 1),
  faithfulness        numeric(4,3) CHECK (faithfulness BETWEEN 0 AND 1),
  citations_valid     boolean,                                  -- cita legible y autorizada para ese usuario
  figures_verified    boolean,                                  -- cada cifra está en una salida de herramienta
  leaked_forbidden    boolean,                                  -- caso negativo: apareció dato prohibido
  passed              boolean NOT NULL,
  notes               varchar(500),
  created_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_ai_evaluations_run_case UNIQUE (run_id, case_id)
);
CREATE INDEX ix_ai_evaluations_case ON ai_evaluations (case_id);
CREATE INDEX ix_ai_evaluations_message ON ai_evaluations (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX ix_ai_evaluations_failed ON ai_evaluations (run_id) WHERE passed = false;
```

# Módulo `customers`

### customers
```sql
CREATE TABLE customers (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_number      varchar(16) NOT NULL,
  customer_type        varchar(12) NOT NULL CHECK (customer_type IN ('INDIVIDUAL','COMPANY')),
  first_name           varchar(100),
  last_name            varchar(150),
  company_name         varchar(200),
  tax_id               varchar(20),                              -- ficticio
  email                varchar(254),
  phone                varchar(32),
  birth_date           date,
  address_line         varchar(200),
  city                 varchar(100),
  postal_code          varchar(10),
  country              char(2) NOT NULL DEFAULT 'ES' CHECK (country ~ '^[A-Z]{2}$'),
  assigned_employee_id uuid REFERENCES employees(id),             -- comercial responsable (alcance OWN)
  marketing_consent    boolean NOT NULL DEFAULT false,
  acquisition_channel  varchar(20) CHECK (acquisition_channel IN ('WEB','REFERRAL','PHONE','PARTNER','CAMPAIGN')),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  deleted_at           timestamptz,
  anonymized_at        timestamptz,                               -- derecho de supresión sin perder agregados
  CONSTRAINT ck_customers_name CHECK (
    (customer_type = 'INDIVIDUAL' AND first_name IS NOT NULL AND last_name IS NOT NULL)
    OR (customer_type = 'COMPANY' AND company_name IS NOT NULL))
);
CREATE UNIQUE INDEX uq_customers_number ON customers (customer_number);
CREATE UNIQUE INDEX uq_customers_tax_id_active ON customers (tax_id) WHERE tax_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_customers_assigned ON customers (assigned_employee_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_customers_name ON customers (lower(last_name), lower(first_name)) WHERE deleted_at IS NULL;
```

# Módulo `insurance` (modelo de corredor: sin siniestros ni provisiones)

### insurance_companies
Aseguradoras con las que trabaja SegurPlus.
```sql
CREATE TABLE insurance_companies (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code             varchar(16)  NOT NULL,
  name             varchar(150) NOT NULL,
  tax_id           varchar(20),
  contact_email    varchar(254),
  agreement_start  date NOT NULL,
  agreement_end    date,
  active           boolean NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  CONSTRAINT ck_insurance_companies_agreement CHECK (agreement_end IS NULL OR agreement_end >= agreement_start)
);
CREATE UNIQUE INDEX uq_insurance_companies_code ON insurance_companies (code) WHERE deleted_at IS NULL;
```

### insurance_products
```sql
CREATE TABLE insurance_products (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  insurance_company_id     uuid NOT NULL REFERENCES insurance_companies(id),
  line                     varchar(8) NOT NULL CHECK (line IN ('AUTO','HOME','LIFE','HEALTH')),
  code                     varchar(24) NOT NULL,
  name                     varchar(150) NOT NULL,
  description              text,
  insurer_commission_rate  numeric(5,4) NOT NULL CHECK (insurer_commission_rate BETWEEN 0 AND 1),  -- parámetro A1, a calibrar
  active                   boolean NOT NULL DEFAULT true,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  deleted_at               timestamptz
);
CREATE UNIQUE INDEX uq_insurance_products_code ON insurance_products (insurance_company_id, code) WHERE deleted_at IS NULL;
CREATE INDEX ix_insurance_products_line ON insurance_products (line) WHERE active;
```

### policies
```sql
CREATE TABLE policies (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_number      varchar(24) NOT NULL,
  sale_id            uuid NOT NULL REFERENCES sales(id),          -- póliza→venta (alta); sales no depende de insurance en código
  customer_id        uuid NOT NULL REFERENCES customers(id),
  insurance_product_id uuid NOT NULL REFERENCES insurance_products(id),
  status             varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING','ACTIVE','CANCELLED','EXPIRED')),
  start_date         date NOT NULL,
  end_date           date NOT NULL,
  annual_premium     numeric(14,2) NOT NULL CHECK (annual_premium > 0),
  currency           char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  payment_frequency  varchar(12) NOT NULL DEFAULT 'ANNUAL' CHECK (payment_frequency IN ('ANNUAL','SEMIANNUAL','QUARTERLY','MONTHLY')),
  risk_data          jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(risk_data) = 'object'),   -- bien/vehículo/asegurado ficticios
  version            bigint NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_policies_number UNIQUE (policy_number),
  CONSTRAINT uq_policies_sale UNIQUE (sale_id),
  CONSTRAINT ck_policies_dates CHECK (end_date > start_date)
);
CREATE INDEX ix_policies_customer ON policies (customer_id);
CREATE INDEX ix_policies_product ON policies (insurance_product_id);
CREATE INDEX ix_policies_renewal_radar ON policies (end_date) WHERE status = 'ACTIVE';
CREATE INDEX ix_policies_status_start ON policies (status, start_date);
```
No tiene `employee_id`: el comercial se obtiene por `sales.employee_id` (una sola fuente).

### policy_coverages
```sql
CREATE TABLE policy_coverages (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_id      uuid NOT NULL REFERENCES policies(id),
  coverage_code  varchar(32)  NOT NULL,
  name           varchar(150) NOT NULL,
  insured_limit  numeric(14,2) CHECK (insured_limit >= 0),
  deductible     numeric(14,2) NOT NULL DEFAULT 0 CHECK (deductible >= 0),
  premium_share  numeric(14,2) CHECK (premium_share >= 0),
  currency       char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_policy_coverages UNIQUE (policy_id, coverage_code)
);
```
Los `UNIQUE` ya sirven de índice por `policy_id`.

### policy_renewals
```sql
CREATE TABLE policy_renewals (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_id              uuid NOT NULL REFERENCES policies(id),
  due_date               date NOT NULL,
  status                 varchar(12) NOT NULL DEFAULT 'UPCOMING' CHECK (status IN ('UPCOMING','RENEWED','LAPSED','CANCELLED')),
  previous_premium       numeric(14,2) NOT NULL CHECK (previous_premium > 0),
  new_premium            numeric(14,2) CHECK (new_premium > 0),
  currency               char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  renewed_at             date,
  new_end_date           date,
  handled_by_employee_id uuid REFERENCES employees(id),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_policy_renewals UNIQUE (policy_id, due_date),
  CONSTRAINT ck_policy_renewals_renewed CHECK (status <> 'RENEWED' OR (new_premium IS NOT NULL AND renewed_at IS NOT NULL AND new_end_date IS NOT NULL))
);
CREATE INDEX ix_policy_renewals_due ON policy_renewals (due_date) WHERE status = 'UPCOMING';
CREATE INDEX ix_policy_renewals_handler ON policy_renewals (handled_by_employee_id) WHERE handled_by_employee_id IS NOT NULL;
```

### policy_cancellations
```sql
CREATE TABLE policy_cancellations (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_id           uuid NOT NULL REFERENCES policies(id),
  requested_on        date NOT NULL,
  effective_date      date NOT NULL,
  reason              varchar(16) NOT NULL CHECK (reason IN ('CUSTOMER_REQUEST','NON_PAYMENT','INSURER_DECISION','DUPLICATE','OTHER')),
  initiated_by        varchar(8)  NOT NULL CHECK (initiated_by IN ('CUSTOMER','INSURER','BROKER')),
  refunded_premium    numeric(14,2) NOT NULL DEFAULT 0 CHECK (refunded_premium >= 0),
  currency            char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  reverses_commission boolean NOT NULL DEFAULT false,   -- storno de comisiones (empleado y asegurador)
  created_by_user_id  uuid NOT NULL REFERENCES users(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_policy_cancellations_policy UNIQUE (policy_id),
  CONSTRAINT ck_policy_cancellations_dates CHECK (effective_date >= requested_on)
);
CREATE INDEX ix_policy_cancellations_effective ON policy_cancellations (effective_date);
CREATE INDEX ix_policy_cancellations_user ON policy_cancellations (created_by_user_id);
```

# Módulo `sales` (CRM)

### leads
```sql
CREATE TABLE leads (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id          uuid REFERENCES customers(id),         -- si ya es cliente
  contact_name         varchar(160) NOT NULL,
  contact_email        varchar(254),
  contact_phone        varchar(32),
  source_channel       varchar(12) NOT NULL CHECK (source_channel IN ('WEB','REFERRAL','PHONE','PARTNER','CAMPAIGN')),
  product_line         varchar(8) CHECK (product_line IN ('AUTO','HOME','LIFE','HEALTH')),
  status               varchar(12) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','CONTACTED','QUALIFIED','CONVERTED','LOST')),
  assigned_employee_id uuid NOT NULL REFERENCES employees(id),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  deleted_at           timestamptz
);
CREATE INDEX ix_leads_assigned_status ON leads (assigned_employee_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ix_leads_customer ON leads (customer_id) WHERE customer_id IS NOT NULL;
```

### opportunities
```sql
CREATE TABLE opportunities (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lead_id               uuid REFERENCES leads(id),
  customer_id           uuid NOT NULL REFERENCES customers(id),
  employee_id           uuid NOT NULL REFERENCES employees(id),
  insurance_product_id  uuid NOT NULL REFERENCES insurance_products(id),
  stage                 varchar(12) NOT NULL DEFAULT 'QUOTED' CHECK (stage IN ('QUOTED','NEGOTIATION','WON','LOST')),
  expected_premium      numeric(14,2) NOT NULL CHECK (expected_premium > 0),
  currency              char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  probability           numeric(3,2) CHECK (probability BETWEEN 0 AND 1),
  expected_close_date   date,
  closed_at             timestamptz,
  lost_reason           varchar(200),
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  deleted_at            timestamptz,
  CONSTRAINT ck_opportunities_closed CHECK ((stage IN ('WON','LOST')) = (closed_at IS NOT NULL)),
  CONSTRAINT ck_opportunities_lost CHECK (stage = 'LOST' OR lost_reason IS NULL)
);
CREATE INDEX ix_opportunities_employee_stage ON opportunities (employee_id, stage) WHERE deleted_at IS NULL;
CREATE INDEX ix_opportunities_customer ON opportunities (customer_id);
CREATE INDEX ix_opportunities_product ON opportunities (insurance_product_id);
CREATE INDEX ix_opportunities_lead ON opportunities (lead_id) WHERE lead_id IS NOT NULL;
CREATE INDEX ix_opportunities_open_close ON opportunities (expected_close_date) WHERE stage IN ('QUOTED','NEGOTIATION');
```

### sales
Altas (nuevo negocio). Una venta confirmada **no cambia de estado** cuando la póliza se cancela después (se cancela la póliza y se revierte la comisión): así los cierres mensuales no se reescriben.
```sql
CREATE TABLE sales (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sale_number    varchar(20) NOT NULL,
  opportunity_id uuid REFERENCES opportunities(id),
  customer_id    uuid NOT NULL REFERENCES customers(id),       -- venta→cliente
  employee_id    uuid NOT NULL REFERENCES employees(id),       -- venta→empleado
  sale_date      date NOT NULL,
  premium_amount numeric(14,2) NOT NULL CHECK (premium_amount > 0),   -- prima anual de la 1.ª vigencia (base de objetivos y comisión)
  currency       char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status         varchar(10) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED','VOID')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_sales_number UNIQUE (sale_number),
  CONSTRAINT uq_sales_opportunity UNIQUE (opportunity_id)
);
CREATE INDEX ix_sales_employee_date ON sales (employee_id, sale_date) WHERE status = 'CONFIRMED';
CREATE INDEX ix_sales_date ON sales (sale_date) WHERE status = 'CONFIRMED';
CREATE INDEX ix_sales_customer ON sales (customer_id);
```
Números ancla de octubre 2026: `COUNT(*)=12` y `SUM(premium_amount)=9680.00` con `sale_date` en octubre y `status='CONFIRMED'`.

### sales_activities
```sql
CREATE TABLE sales_activities (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id    uuid NOT NULL REFERENCES employees(id),
  customer_id    uuid REFERENCES customers(id),
  lead_id        uuid REFERENCES leads(id),
  opportunity_id uuid REFERENCES opportunities(id),
  activity_type  varchar(12) NOT NULL CHECK (activity_type IN ('CALL','EMAIL','MEETING','QUOTE','FOLLOW_UP')),
  occurred_at    timestamptz NOT NULL,
  duration_min   smallint CHECK (duration_min >= 0),
  outcome        varchar(200),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_sales_activities_target CHECK (num_nonnulls(customer_id, lead_id, opportunity_id) >= 1)
);
CREATE INDEX ix_sales_activities_employee ON sales_activities (employee_id, occurred_at DESC);
CREATE INDEX ix_sales_activities_customer ON sales_activities (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_sales_activities_lead ON sales_activities (lead_id) WHERE lead_id IS NOT NULL;
CREATE INDEX ix_sales_activities_opportunity ON sales_activities (opportunity_id) WHERE opportunity_id IS NOT NULL;
```

# Módulo `finance`

### accounting_accounts
Plan de cuentas reducido (inspirado en el PGC español; no es contabilidad oficial).
```sql
CREATE TABLE accounting_accounts (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code         varchar(10)  NOT NULL,                   -- 4000 Proveedores, 572 Bancos, 640 Sueldos y salarios, 705 Comisiones...
  name         varchar(150) NOT NULL,
  account_type varchar(10)  NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
  active       boolean NOT NULL DEFAULT true,
  CONSTRAINT uq_accounting_accounts_code UNIQUE (code)
);
```

### accounting_entries
Cabecera de asiento.
```sql
CREATE TABLE accounting_entries (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_number        bigint GENERATED ALWAYS AS IDENTITY,
  entry_date          date NOT NULL,
  description         varchar(300) NOT NULL,
  source_type         varchar(16) NOT NULL CHECK (source_type IN ('EXPENSE','INVOICE','PAYMENT','COMMISSION','PAYROLL','MANUAL')),
  source_id           uuid,                                  -- id del origen (sin FK: tablas distintas)
  status              varchar(10) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED')),
  reversal_of_entry_id uuid REFERENCES accounting_entries(id),
  created_by_user_id  uuid REFERENCES users(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_accounting_entries_number UNIQUE (entry_number)
);
CREATE INDEX ix_accounting_entries_date ON accounting_entries (entry_date);
CREATE INDEX ix_accounting_entries_creator ON accounting_entries (created_by_user_id) WHERE created_by_user_id IS NOT NULL;
CREATE INDEX ix_accounting_entries_source ON accounting_entries (source_type, source_id) WHERE source_id IS NOT NULL;
CREATE INDEX ix_accounting_entries_reversal ON accounting_entries (reversal_of_entry_id) WHERE reversal_of_entry_id IS NOT NULL;
```

### accounting_entry_lines
```sql
CREATE TABLE accounting_entry_lines (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_id    uuid NOT NULL REFERENCES accounting_entries(id),
  line_no     smallint NOT NULL CHECK (line_no >= 1),
  account_id  uuid NOT NULL REFERENCES accounting_accounts(id),
  debit       numeric(14,2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
  credit      numeric(14,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
  currency    char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  description varchar(300),
  CONSTRAINT uq_accounting_entry_lines UNIQUE (entry_id, line_no),
  CONSTRAINT ck_accounting_entry_lines_side CHECK ((debit = 0) <> (credit = 0))
);
CREATE INDEX ix_accounting_entry_lines_account ON accounting_entry_lines (account_id, entry_id);
```
Invariantes por trigger diferido: por asiento `SUM(debit) = SUM(credit)` y una sola moneda. La línea «Sueldos y salarios» de cada mes es **agregada** (A5); sin detalle individual.

### expense_categories
```sql
CREATE TABLE expense_categories (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                  varchar(24)  NOT NULL,   -- RENT, SOFTWARE, MARKETING, PROFESSIONAL_SERVICES, UTILITIES, TRAVEL, SUPPLIES, OTHER
  name                  varchar(100) NOT NULL,
  accounting_account_id uuid NOT NULL REFERENCES accounting_accounts(id),
  active                boolean NOT NULL DEFAULT true,
  CONSTRAINT uq_expense_categories_code UNIQUE (code),
  CONSTRAINT ck_expense_categories_no_payroll CHECK (code NOT IN ('PAYROLL','COMMISSIONS','SALARIES'))
);
CREATE INDEX ix_expense_categories_account ON expense_categories (accounting_account_id);
```
Nóminas y comisiones **no** son gastos de esta tabla (brief §6).

### invoices
Modelo de corredor: las **emitidas** van a la aseguradora (liquidación de comisiones); las **recibidas** son de proveedores y se enlazan con `expenses`.
```sql
CREATE TABLE invoices (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  direction            varchar(8) NOT NULL CHECK (direction IN ('ISSUED','RECEIVED')),
  invoice_number       varchar(40) NOT NULL,
  insurance_company_id uuid REFERENCES insurance_companies(id),   -- ISSUED
  supplier_name        varchar(150),                              -- RECEIVED
  issue_date           date NOT NULL,
  due_date             date,
  period_month         date CHECK (period_month IS NULL OR EXTRACT(DAY FROM period_month) = 1),
  net_amount           numeric(14,2) NOT NULL CHECK (net_amount >= 0),
  tax_amount           numeric(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
  total_amount         numeric(14,2) NOT NULL,
  currency             char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status               varchar(8) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('DRAFT','OPEN','PAID','VOID')),
  accounting_entry_id  uuid REFERENCES accounting_entries(id),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_invoices_total CHECK (total_amount = net_amount + tax_amount),
  CONSTRAINT ck_invoices_counterparty CHECK (
    (direction = 'ISSUED'   AND insurance_company_id IS NOT NULL AND supplier_name IS NULL) OR
    (direction = 'RECEIVED' AND supplier_name IS NOT NULL AND insurance_company_id IS NULL)),
  CONSTRAINT ck_invoices_due CHECK (due_date IS NULL OR due_date >= issue_date)
);
CREATE UNIQUE INDEX uq_invoices_issued_number ON invoices (invoice_number) WHERE direction = 'ISSUED';
CREATE UNIQUE INDEX uq_invoices_received_number ON invoices (supplier_name, invoice_number) WHERE direction = 'RECEIVED';
CREATE INDEX ix_invoices_period ON invoices (period_month, direction);
CREATE INDEX ix_invoices_company ON invoices (insurance_company_id) WHERE insurance_company_id IS NOT NULL;
CREATE INDEX ix_invoices_open ON invoices (due_date) WHERE status = 'OPEN';
CREATE INDEX ix_invoices_entry ON invoices (accounting_entry_id) WHERE accounting_entry_id IS NOT NULL;
```

### insurer_commissions
Lo que **la aseguradora paga al corredor** (ingreso de SegurPlus). `insurer_commission_rate` se copia del producto al devengar.
```sql
CREATE TABLE insurer_commissions (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_id               uuid NOT NULL REFERENCES policies(id),
  sale_id                 uuid NOT NULL REFERENCES sales(id),
  renewal_id              uuid REFERENCES policy_renewals(id),
  insurance_company_id    uuid NOT NULL REFERENCES insurance_companies(id),
  kind                    varchar(12) NOT NULL CHECK (kind IN ('NEW_BUSINESS','RENEWAL')),
  base_premium            numeric(14,2) NOT NULL CHECK (base_premium > 0),
  insurer_commission_rate numeric(5,4) NOT NULL CHECK (insurer_commission_rate BETWEEN 0 AND 1),
  amount                  numeric(14,2) NOT NULL,
  currency                char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  accrual_date            date NOT NULL,
  period_month            date NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
  status                  varchar(12) NOT NULL DEFAULT 'ACCRUED' CHECK (status IN ('ACCRUED','INVOICED','RECEIVED','CLAWED_BACK')),
  invoice_id              uuid REFERENCES invoices(id),
  clawback_of_id          uuid REFERENCES insurer_commissions(id),
  created_at              timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_insurer_commissions_amount CHECK (amount = round(base_premium * insurer_commission_rate, 2) OR clawback_of_id IS NOT NULL),
  CONSTRAINT ck_insurer_commissions_renewal CHECK ((kind = 'RENEWAL') = (renewal_id IS NOT NULL)),
  CONSTRAINT ck_insurer_commissions_invoiced CHECK (status NOT IN ('INVOICED','RECEIVED') OR invoice_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_insurer_commissions_new ON insurer_commissions (policy_id) WHERE kind = 'NEW_BUSINESS' AND clawback_of_id IS NULL;
CREATE UNIQUE INDEX uq_insurer_commissions_renewal ON insurer_commissions (renewal_id) WHERE renewal_id IS NOT NULL AND clawback_of_id IS NULL;
CREATE INDEX ix_insurer_commissions_period ON insurer_commissions (period_month, status);
CREATE INDEX ix_insurer_commissions_company ON insurer_commissions (insurance_company_id, period_month);
CREATE INDEX ix_insurer_commissions_sale ON insurer_commissions (sale_id);
CREATE INDEX ix_insurer_commissions_invoice ON insurer_commissions (invoice_id) WHERE invoice_id IS NOT NULL;
CREATE INDEX ix_insurer_commissions_clawback ON insurer_commissions (clawback_of_id) WHERE clawback_of_id IS NOT NULL;
```
Los clawbacks tienen importe negativo conceptual; se modelan como fila con `clawback_of_id` (el importe se guarda positivo y el signo lo da el vínculo).

### commissions
Comisión **del empleado** (venta→empleado). Tasa del contrato (`employment_contracts.commission_rate`, 10 % en el brief).
```sql
CREATE TABLE commissions (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id         uuid NOT NULL REFERENCES employees(id),
  sale_id             uuid NOT NULL REFERENCES sales(id),         -- comisión→venta
  base_amount         numeric(14,2) NOT NULL CHECK (base_amount > 0),
  rate                numeric(5,4)  NOT NULL CHECK (rate BETWEEN 0 AND 1),
  amount              numeric(14,2) NOT NULL CHECK (amount >= 0),
  currency            char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  period_month        date NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
  status              varchar(10) NOT NULL DEFAULT 'ACCRUED' CHECK (status IN ('ACCRUED','APPROVED','PAID','REVERSED')),
  payment_id          uuid REFERENCES payments(id),
  accounting_entry_id uuid REFERENCES accounting_entries(id),
  cancellation_id     uuid REFERENCES policy_cancellations(id),    -- si se revierte por cancelación
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_commissions_sale UNIQUE (sale_id),
  CONSTRAINT ck_commissions_amount CHECK (amount = round(base_amount * rate, 2)),
  CONSTRAINT ck_commissions_paid CHECK (status <> 'PAID' OR payment_id IS NOT NULL),
  CONSTRAINT ck_commissions_reversed CHECK ((status = 'REVERSED') = (cancellation_id IS NOT NULL))
);
CREATE INDEX ix_commissions_employee_period ON commissions (employee_id, period_month) WHERE status <> 'REVERSED';
CREATE INDEX ix_commissions_period ON commissions (period_month, status);
CREATE INDEX ix_commissions_payment ON commissions (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX ix_commissions_entry ON commissions (accounting_entry_id) WHERE accounting_entry_id IS NOT NULL;
CREATE INDEX ix_commissions_cancellation ON commissions (cancellation_id) WHERE cancellation_id IS NOT NULL;
```
La comisión se redondea **por venta** (HALF_UP a 2 decimales). Con las primas del brief (múltiplos de 10 €) el total mensual equivale a 10 % de las primas (968 €).

### payments
También sirve de movimiento bancario para la conciliación (D09/D10).
```sql
CREATE TABLE payments (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  direction             varchar(3) NOT NULL CHECK (direction IN ('IN','OUT')),
  payment_type          varchar(20) NOT NULL CHECK (payment_type IN ('INVOICE_SETTLEMENT','PAYROLL','EMPLOYEE_COMMISSION','TAX','OTHER')),
  invoice_id            uuid REFERENCES invoices(id),
  payroll_run_id        uuid REFERENCES payroll_runs(id),
  amount                numeric(14,2) NOT NULL CHECK (amount > 0),
  currency              char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  value_date            date NOT NULL,
  method                varchar(14) NOT NULL DEFAULT 'BANK_TRANSFER' CHECK (method IN ('BANK_TRANSFER','DIRECT_DEBIT','CARD','CASH')),
  bank_reference        varchar(64),
  reconciliation_status varchar(12) NOT NULL DEFAULT 'UNRECONCILED' CHECK (reconciliation_status IN ('UNRECONCILED','RECONCILED','DISPUTED')),
  reconciled_at         timestamptz,
  accounting_entry_id   uuid REFERENCES accounting_entries(id),
  created_at            timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_payments_source CHECK (
    (payment_type = 'INVOICE_SETTLEMENT') = (invoice_id IS NOT NULL)
    AND (payment_type = 'PAYROLL') = (payroll_run_id IS NOT NULL)),
  CONSTRAINT ck_payments_reconciled CHECK ((reconciliation_status = 'RECONCILED') = (reconciled_at IS NOT NULL))
);
CREATE INDEX ix_payments_value_date ON payments (value_date);
CREATE INDEX ix_payments_unreconciled ON payments (value_date) WHERE reconciliation_status <> 'RECONCILED';
CREATE INDEX ix_payments_invoice ON payments (invoice_id) WHERE invoice_id IS NOT NULL;
CREATE INDEX ix_payments_payroll ON payments (payroll_run_id) WHERE payroll_run_id IS NOT NULL;
CREATE INDEX ix_payments_entry ON payments (accounting_entry_id) WHERE accounting_entry_id IS NOT NULL;
```

### expenses
```sql
CREATE TABLE expenses (
  id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  expense_number            varchar(20) NOT NULL,
  expense_date              date NOT NULL,
  category_id               uuid NOT NULL REFERENCES expense_categories(id),
  supplier_name             varchar(150) NOT NULL,
  description               varchar(300) NOT NULL,
  amount                    numeric(14,2) NOT NULL CHECK (amount >= 0),        -- base
  tax_amount                numeric(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
  total_amount              numeric(14,2) NOT NULL,                            -- cifra del brief (H-16)
  currency                  char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  status                    varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','PAID','VOID')),
  invoice_id                uuid REFERENCES invoices(id),
  accounting_entry_id       uuid REFERENCES accounting_entries(id),           -- gasto→asiento
  submitted_by_employee_id  uuid REFERENCES employees(id),
  approved_by_employee_id   uuid REFERENCES employees(id),
  created_at                timestamptz NOT NULL DEFAULT now(),
  updated_at                timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_expenses_number UNIQUE (expense_number),
  CONSTRAINT ck_expenses_total CHECK (total_amount = amount + tax_amount),
  CONSTRAINT ck_expenses_posted CHECK (status = 'DRAFT' OR accounting_entry_id IS NOT NULL)
);
CREATE INDEX ix_expenses_date_category ON expenses (expense_date, category_id) WHERE status <> 'VOID';
CREATE INDEX ix_expenses_category ON expenses (category_id);
CREATE INDEX ix_expenses_invoice ON expenses (invoice_id) WHERE invoice_id IS NOT NULL;
CREATE INDEX ix_expenses_entry ON expenses (accounting_entry_id) WHERE accounting_entry_id IS NOT NULL;
CREATE INDEX ix_expenses_submitter ON expenses (submitted_by_employee_id) WHERE submitted_by_employee_id IS NOT NULL;
CREATE INDEX ix_expenses_approver ON expenses (approved_by_employee_id) WHERE approved_by_employee_id IS NOT NULL;
```

# Módulo `hr`

### employment_contracts
Contienen salario: dato RESTRICTED. El propio empleado y MANAGER lo leen; ningún otro rol.
```sql
CREATE TABLE employment_contracts (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id         uuid NOT NULL REFERENCES employees(id),
  contract_type       varchar(12) NOT NULL CHECK (contract_type IN ('PERMANENT','FIXED_TERM','INTERNSHIP')),
  start_date          date NOT NULL,
  end_date            date,
  base_salary_annual  numeric(14,2) NOT NULL CHECK (base_salary_annual > 0),
  currency            char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  commission_rate     numeric(5,4) NOT NULL DEFAULT 0 CHECK (commission_rate BETWEEN 0 AND 1),   -- 0,1000 en el brief
  weekly_hours        numeric(4,1) NOT NULL DEFAULT 40 CHECK (weekly_hours > 0 AND weekly_hours <= 60),
  status              varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED')),
  document_id         uuid REFERENCES documents(id),               -- contrato escaneado/generado (D14, D17...)
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_employment_contracts_dates CHECK (end_date IS NULL OR end_date >= start_date),
  CONSTRAINT ex_employment_contracts_overlap EXCLUDE USING gist
    (employee_id WITH =, daterange(start_date, end_date, '[]') WITH &&) WHERE (status = 'ACTIVE')   -- requiere btree_gist
);
CREATE INDEX ix_employment_contracts_employee ON employment_contracts (employee_id, start_date DESC);
CREATE INDEX ix_employment_contracts_document ON employment_contracts (document_id) WHERE document_id IS NOT NULL;
```

### payroll_runs
Cabecera mensual **agregada**. Es lo único de nóminas que ve Contabilidad (A5), a través de la vista `v_payroll_monthly_aggregate`.
```sql
CREATE TABLE payroll_runs (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  period_month         date NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
  status               varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','POSTED','PAID')),
  headcount            integer NOT NULL CHECK (headcount >= 0),
  total_gross          numeric(14,2) NOT NULL CHECK (total_gross >= 0),
  total_commissions    numeric(14,2) NOT NULL DEFAULT 0 CHECK (total_commissions >= 0),
  total_net            numeric(14,2) NOT NULL CHECK (total_net >= 0),
  total_employer_cost  numeric(14,2) NOT NULL CHECK (total_employer_cost >= 0),
  currency             char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  accounting_entry_id  uuid REFERENCES accounting_entries(id),     -- línea agregada «Sueldos y salarios»
  approved_by_user_id  uuid REFERENCES users(id),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_payroll_runs_period UNIQUE (period_month),
  CONSTRAINT ck_payroll_runs_posted CHECK (status IN ('DRAFT','APPROVED') OR accounting_entry_id IS NOT NULL)
);
CREATE INDEX ix_payroll_runs_entry ON payroll_runs (accounting_entry_id) WHERE accounting_entry_id IS NOT NULL;
CREATE INDEX ix_payroll_runs_approved_by ON payroll_runs (approved_by_user_id) WHERE approved_by_user_id IS NOT NULL;
```
Vista (documentación): `v_payroll_monthly_aggregate(period_month, headcount, total_gross, total_employer_cost, currency)` sin columnas por empleado; no depende del usuario.

### payroll
Nómina individual (**RESTRICTED**): propietario y MANAGER.
```sql
CREATE TABLE payroll (
  id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payroll_run_id             uuid NOT NULL REFERENCES payroll_runs(id),
  employee_id                uuid NOT NULL REFERENCES employees(id),
  contract_id                uuid NOT NULL REFERENCES employment_contracts(id),
  base_salary                numeric(14,2) NOT NULL CHECK (base_salary >= 0),
  commissions_amount         numeric(14,2) NOT NULL DEFAULT 0 CHECK (commissions_amount >= 0),
  other_earnings             numeric(14,2) NOT NULL DEFAULT 0 CHECK (other_earnings >= 0),
  gross_amount               numeric(14,2) NOT NULL,
  social_security_employee   numeric(14,2) NOT NULL DEFAULT 0 CHECK (social_security_employee >= 0),
  income_tax_withholding     numeric(14,2) NOT NULL DEFAULT 0 CHECK (income_tax_withholding >= 0),
  net_amount                 numeric(14,2) NOT NULL,
  employer_social_security   numeric(14,2) NOT NULL DEFAULT 0 CHECK (employer_social_security >= 0),
  currency                   char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  created_at                 timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_payroll_run_employee UNIQUE (payroll_run_id, employee_id),
  CONSTRAINT ck_payroll_gross CHECK (gross_amount = base_salary + commissions_amount + other_earnings),
  CONSTRAINT ck_payroll_net CHECK (net_amount = gross_amount - social_security_employee - income_tax_withholding)
);
CREATE INDEX ix_payroll_employee ON payroll (employee_id, payroll_run_id);
CREATE INDEX ix_payroll_contract ON payroll (contract_id);
```
`commissions_amount` de un mes = suma de `commissions.amount` de ese `period_month` y empleado (se prueba en F2).

### sales_targets
```sql
CREATE TABLE sales_targets (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id   uuid NOT NULL REFERENCES employees(id),
  period_month  date NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
  target_amount numeric(14,2) NOT NULL CHECK (target_amount > 0),    -- sobre prima de ventas
  currency      char(3) NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
  set_by_user_id uuid REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_sales_targets UNIQUE (employee_id, period_month)
);
CREATE INDEX ix_sales_targets_period ON sales_targets (period_month);
CREATE INDEX ix_sales_targets_set_by ON sales_targets (set_by_user_id) WHERE set_by_user_id IS NOT NULL;
```
Octubre 2026: 3.000 / 3.000 / 2.800 / 3.200 → 12.000 €.

### performance_reviews
```sql
CREATE TABLE performance_reviews (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id          uuid NOT NULL REFERENCES employees(id),
  reviewer_employee_id uuid NOT NULL REFERENCES employees(id),
  period_start         date NOT NULL,
  period_end           date NOT NULL,
  rating               smallint CHECK (rating BETWEEN 1 AND 5),
  summary              text,
  status               varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED')),
  completed_at         timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_performance_reviews_period CHECK (period_end >= period_start),
  CONSTRAINT ck_performance_reviews_reviewer CHECK (reviewer_employee_id <> employee_id)
);
CREATE INDEX ix_performance_reviews_employee ON performance_reviews (employee_id, period_end DESC);
CREATE INDEX ix_performance_reviews_reviewer ON performance_reviews (reviewer_employee_id);
```

### leave_requests
```sql
CREATE TABLE leave_requests (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id          uuid NOT NULL REFERENCES employees(id),
  leave_type           varchar(10) NOT NULL CHECK (leave_type IN ('VACATION','SICK','PERSONAL','UNPAID','OTHER')),
  start_date           date NOT NULL,
  end_date             date NOT NULL,
  days                 numeric(4,1) NOT NULL CHECK (days > 0),
  status               varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  reviewer_employee_id uuid REFERENCES employees(id),
  decided_at           timestamptz,
  reason               varchar(300),                          -- sin datos médicos
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_leave_requests_dates CHECK (end_date >= start_date),
  CONSTRAINT ck_leave_requests_decided CHECK ((status IN ('APPROVED','REJECTED')) = (decided_at IS NOT NULL))
);
CREATE INDEX ix_leave_requests_employee ON leave_requests (employee_id, start_date DESC);
CREATE INDEX ix_leave_requests_pending ON leave_requests (start_date) WHERE status = 'PENDING';
CREATE INDEX ix_leave_requests_reviewer ON leave_requests (reviewer_employee_id) WHERE reviewer_employee_id IS NOT NULL;
```

# Módulo `audit`

### audit_logs
Append-only, **particionada por mes**, solo metadatos. Sin contenido de preguntas, respuestas, fragmentos ni JWT.
```sql
CREATE TABLE audit_logs (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  occurred_at     timestamptz NOT NULL,                       -- reloj de SISTEMA
  business_date   date NOT NULL,                              -- reloj de negocio en ese momento
  actor_user_id   uuid,                                       -- sin FK (ver nota)
  actor_roles     varchar(100),                               -- snapshot, p. ej. 'EMPLOYEE'
  event_type      varchar(40) NOT NULL,                       -- LOGIN_SUCCESS, LOGIN_FAILURE, TOKEN_REUSE, ACCESS_DENIED, DOCUMENT_READ,
                                                              -- DOCUMENT_DOWNLOAD, DOCUMENT_UPLOAD, GRANT_CHANGED, CHAT_TURN, TOOL_CALL,
                                                              -- RAG_FRAGMENT_DROPPED, INDEXING_STATE, RECONCILIATION_RUN, IGNORED_CLIENT_FIELD...
  outcome         varchar(8)  NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','ERROR')),
  channel         varchar(10) CHECK (channel IN ('REST','AI_TOOL','RAG','JOB','AUTH')),
  action          varchar(16) CHECK (action IN ('READ','CREATE','UPDATE','DELETE','DOWNLOAD','ADMINISTER')),
  resource_type   varchar(32),
  resource_id     uuid,
  correlation_id  uuid NOT NULL,
  client_ip_hash  char(64),                                   -- SHA-256 con sal rotatoria; nunca la IP en claro
  details         jsonb NOT NULL DEFAULT '{}'::jsonb
                  CHECK (jsonb_typeof(details) = 'object' AND pg_column_size(details) <= 2048),  -- lista blanca de claves en app
  PRIMARY KEY (id, occurred_at)                               -- la clave de partición debe estar en la PK
) PARTITION BY RANGE (occurred_at);
-- Una partición por mes, p. ej. audit_logs_2026_10 FOR VALUES FROM ('2026-10-01') TO ('2026-11-01'), creada por un job con antelación.
CREATE INDEX ix_audit_logs_actor_time    ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_event_time    ON audit_logs (event_type, occurred_at DESC);
CREATE INDEX ix_audit_logs_resource      ON audit_logs (resource_type, resource_id, occurred_at DESC) WHERE resource_id IS NOT NULL;
CREATE INDEX ix_audit_logs_correlation   ON audit_logs (correlation_id);
CREATE INDEX ix_audit_logs_denied        ON audit_logs (occurred_at DESC) WHERE outcome = 'DENIED';
CREATE INDEX ix_audit_logs_downloads     ON audit_logs (actor_user_id, occurred_at DESC) WHERE event_type = 'DOCUMENT_DOWNLOAD';  -- regla de descargas masivas
CREATE INDEX ix_audit_logs_time_brin     ON audit_logs USING brin (occurred_at);
```
- **Append-only**: el rol de la aplicación solo tiene `INSERT` y `SELECT`; `REVOKE UPDATE, DELETE, TRUNCATE`; trigger `BEFORE UPDATE OR DELETE` que lanza excepción (defensa en profundidad). El mantenimiento de particiones usa otro rol.
- **Retención**: se retira por partición (`DETACH`/archivo), nunca fila a fila.
- **Sin FKs** (a `users`, `documents`...): la auditoría debe sobrevivir a anonimizaciones y borrados. La validación «cada FK referenciada existe» no aplica a esta tabla.
- Un test comprueba que ninguna clave de `details` pertenece a `{message, content, prompt, answer, chunk, token, jwt}`.

# Módulo `shared`

### outbox_events
Outbox transaccional para propagar a Dify (ADR 0004). Entrega *at-least-once*; los consumidores son idempotentes por `id`.
```sql
CREATE TABLE outbox_events (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type  varchar(32) NOT NULL,                      -- DOCUMENT
  aggregate_id    uuid NOT NULL,
  event_type      varchar(40) NOT NULL CHECK (event_type IN (
                  'DOCUMENT_INDEX_REQUESTED','DOCUMENT_REINDEX_REQUESTED','DOCUMENT_METADATA_CHANGED',
                  'DOCUMENT_MOVED','DOCUMENT_DELETE_REQUESTED','DOCUMENT_PURGE_REQUESTED')),
  payload         jsonb NOT NULL DEFAULT '{}'::jsonb,         -- solo identificadores y versiones, nunca contenido
  status          varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','DONE','FAILED','DEAD')),
  attempts        integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error_code varchar(64),
  created_at      timestamptz NOT NULL DEFAULT now(),         -- reloj de SISTEMA
  processed_at    timestamptz
);
CREATE INDEX ix_outbox_events_due ON outbox_events (next_attempt_at) WHERE status IN ('PENDING','FAILED');
CREATE INDEX ix_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id, created_at);
CREATE INDEX ix_outbox_events_dead ON outbox_events (created_at) WHERE status = 'DEAD';
```
Alternativa evaluada: el registro de publicaciones de eventos de Spring Modulith (`event_publication`). Se usa para eventos internos entre módulos; esta tabla se mantiene porque el estado de la sincronización con Dify debe ser consultable y tener reintentos/DLQ propios.
