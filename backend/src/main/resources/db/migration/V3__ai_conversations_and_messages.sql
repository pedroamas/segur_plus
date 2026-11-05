-- Generado desde docs/data-model/schema.md (fuente de diseño). Una migración por paquete.

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
