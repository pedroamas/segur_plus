# 01 · Contexto del sistema

SegurPlus es un corredor/mediador de seguros online ficticio (A1). Siete usuarios, cuatro roles, cuatro asistentes de IA.

```mermaid
flowchart LR
  subgraph Usuarios["Usuarios de SegurPlus (datos ficticios)"]
    DIR["DIR001 Director General<br/>rol MANAGER"]
    ACC["ACC001 Responsable Financiero<br/>rol ACCOUNTANT"]
    COM["COM001 Responsable Comercial<br/>rol SALES_MANAGER"]
    EMP["EMP001-EMP004 Comerciales<br/>rol EMPLOYEE"]
  end

  SP["SegurPlus<br/>portal + API + asistentes de IA"]

  DIFY["Dify 1.18<br/>Knowledge API y chat-messages"]
  GEM["Gemini<br/>LLM y embeddings, vía Dify"]
  AV["Antivirus / escáner de ficheros<br/>ClamAV o equivalente"]

  DIR --> SP
  ACC --> SP
  COM --> SP
  EMP --> SP
  SP -->|"HTTPS, claves solo en backend"| DIFY
  DIFY --> GEM
  SP -->|"escaneo previo a indexar"| AV
```

## Fronteras de confianza
| Frontera | Qué cruza | Control |
|---|---|---|
| Navegador → backend | JWT de acceso (10-15 min), refresh en cookie HttpOnly | Spring Security; identidad solo del JWT; se ignoran `employee_id`, `role`, `assistantId`, `datasetId` del cliente |
| Backend → Dify | consultas con filtros de metadatos, contexto delimitado, `user` opaco `u_<uuid>` | claves de app y Knowledge solo en backend; fragmentos re-autorizados al volver |
| Backend → MinIO | objetos privados | solo el backend tiene credenciales; descargas auditadas |
| Contenido de documentos → LLM | texto no confiable | delimitado, sin herramientas de escritura, corpus de prompt injection (criterio 9) |

Fuera del sistema (no existen en SegurPlus): aseguradoras con integración real, pasarela de pago, siniestros. Las aseguradoras son datos de catálogo (`insurance_companies`).
