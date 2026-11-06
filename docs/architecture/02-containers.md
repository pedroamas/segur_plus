# 02 · Contenedores

```mermaid
flowchart TB
  U["Navegador<br/>React + TypeScript + React Router"]

  subgraph WebC["Contenedor web (Caddy, tls internal)<br/>https://192.168.0.28:5174, solo LAN"]
    STATIC["Sirve frontend/dist"]
    PROXY["reverse_proxy /api/*<br/>flush_interval -1"]
  end

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

  U -->|"HTTPS, mismo origen"| STATIC
  U -->|"HTTPS /api/*: JSON, cookie de refresh y SSE (F5)"| PROXY
  PROXY -->|"HTTP host.docker.internal:8100"| API
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
| Frontend (build estático) | React, TS, Vite, React Router, pdf.js | Login, Documentos, Dashboard y Chat (**«no disponible» en F3**; real en F5 con SSE por `fetch` + `ReadableStream`). No decide permisos. Access JWT solo en memoria | Nada sensible persistente |
| Web (Caddy) | Caddy 2, `tls internal` | Sirve `frontend/dist` por HTTPS y proxifica `/api/*` al backend: mismo origen, sin CORS. Ligado a `192.168.0.28:5174` (solo LAN; no abrir en el router) | Solo la CA local de Caddy |
| Backend | Spring Boot, JPA, Flyway | Lógica, política de acceso, orquestación RAG/SQL | Sin estado |
| PostgreSQL | PG 16 | **Fuente de verdad** ([schema.md](../data-model/schema.md)) | 12 tablas |
| MinIO | bucket privado | Originales de documentos | Binarios; clave `documents/<docId>/<sha256>` |
| Dify | 1.18 | Indexación, recuperación, generación | Copia derivada del texto y metadatos; reconstruible |
| Gemini | API externa | LLM; se alcanza a través de Dify | Nada nuestro |

## Despliegue local
`docker compose up -d` levanta postgres, minio, dify (api, worker, redis, vector store), backend y **web** (Caddy con el frontend; en F3 ya existe; el backend corre en el host en :8100 hasta F6). Secretos en `.env` no versionado. Un seed determinista (F2) reproduce el dataset. Si el stream de Dify solo emite `ping`, revisar el worker y Redis de Dify.

## Configuración relevante (nombres orientativos)
`segurplus.clock.fixed-date` (2027-01-15), `segurplus.clock.zone` (Europe/Madrid), `segurplus.dify.base-url`, claves de app y Knowledge por separado, `segurplus.ai.timeouts.*`, `segurplus.assistant.profile` (YAML con KBs y alcance de herramientas por rol).
