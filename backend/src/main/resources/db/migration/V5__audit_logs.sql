-- Generado desde docs/data-model/schema.md (fuente de diseño). Una migración por paquete.

CREATE TABLE audit_logs (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at    timestamptz NOT NULL DEFAULT now(),   -- tiempo real
  business_date  date NOT NULL,                        -- fecha ficticia del Clock
  user_id        uuid,                                 -- sin FK
  action         varchar(24) NOT NULL
                 CHECK (action IN ('LOGIN','LOGIN_FAILED','DOCUMENT_READ','DOCUMENT_DOWNLOAD','DOCUMENT_UPLOAD',
                                   'ACCESS_DENIED','CHAT','TOOL_CALL','FRAGMENT_DROPPED','IGNORED_CLIENT_FIELD',
                                   'TOKEN_REFRESH','TOKEN_REUSE_DETECTED','LOGOUT')),
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
