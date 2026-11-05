-- Generado desde docs/data-model/schema.md (fuente de diseño). Una migración por paquete.

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

CREATE TABLE refresh_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id),
  token_hash  char(64)    NOT NULL UNIQUE,         -- SHA-256 en hex; nunca el token
  family_id   uuid        NOT NULL,                -- misma cadena de rotaciones
  issued_at   timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  replaced_by uuid REFERENCES refresh_tokens(id),  -- token que lo sustituyó al rotar
  CONSTRAINT ck_refresh_expiry CHECK (expires_at > issued_at),
  CONSTRAINT ck_refresh_replaced CHECK (replaced_by IS NULL OR revoked_at IS NOT NULL)
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_replaced ON refresh_tokens (replaced_by) WHERE replaced_by IS NOT NULL;
