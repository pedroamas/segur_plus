# 02 · Contenedores

```mermaid
flowchart TB
  U["Navegador<br/>React + TypeScript + React Router"]

  subgraph Backend["Backend Spring Boot 3, Java 21 (un despliegue)"]
    API["API REST /api/v1<br/>+ SSE del chat"]
    SEC["Spring Security JWT + refresh<br/>+ AccessPolicyService"]
    BIZ["Paquetes documents y data"]
    AIM["Paquete ai<br/>AiGateway + orquestador de chat + indexador"]
  end

  PG[("PostgreSQL<br/>datos, metadatos, auditoría")]
  MINIO[("MinIO<br/>originales, bucket privado")]

  subgraph DifyStack["Dify 1.18 (docker compose)"]
    DAPI["API Dify<br/>/datasets/... y /chat-messages"]
    DW["Worker + Redis<br/>indexación"]
    VDB[("Vector store de Dify")]
  end

  GEM["Gemini"]

  U -->|"HTTP JSON y SSE"| API
  API --> SEC
  SEC --> BIZ
  SEC --> AIM
  BIZ --> PG
  BIZ -->|"S3 API"| MINIO
  AIM --> PG
  AIM -->|"WebClient"| DAPI
  DAPI --> DW
  DW --> VDB
  DAPI --> VDB
  DAPI --> GEM
  DW --> GEM
```

| Contenedor | Tecnología | Responsabilidad | Datos que guarda |
|---|---|---|---|
| Frontend | React, TS, React Router, pdf.js | Login, Documentos, Chat, Dashboard. No decide permisos; consume SSE con `fetch` + `ReadableStream` | Nada sensible persistente |
| Backend | Spring Boot, JPA, Flyway | Lógica, política de acceso, orquestación RAG/SQL | Sin estado |
| PostgreSQL | PG 16 | **Fuente de verdad** ([schema.md](../data-model/schema.md)) | 12 tablas |
| MinIO | bucket privado | Originales de documentos | Binarios; clave `documents/<docId>/<sha256>` |
| Dify | 1.18 | Indexación, recuperación, generación | Copia derivada del texto y metadatos; reconstruible |
| Gemini | API externa | LLM; se alcanza a través de Dify | Nada nuestro |

## Despliegue local
`docker compose up -d` levanta postgres, minio, dify (api, worker, redis, vector store), backend y frontend. Secretos en `.env` no versionado. Un seed determinista (F2) reproduce el dataset. Si el stream de Dify solo emite `ping`, revisar el worker y Redis de Dify.

## Configuración relevante (nombres orientativos)
`segurplus.clock.fixed-date` (2027-01-15), `segurplus.clock.zone` (Europe/Madrid), `segurplus.dify.base-url`, claves de app y Knowledge por separado, `segurplus.ai.timeouts.*`, `segurplus.assistant.profile` (YAML con KBs y alcance de herramientas por rol).
