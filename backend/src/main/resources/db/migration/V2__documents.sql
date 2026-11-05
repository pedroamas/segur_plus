-- Generado desde docs/data-model/schema.md (fuente de diseño). Una migración por paquete.

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
