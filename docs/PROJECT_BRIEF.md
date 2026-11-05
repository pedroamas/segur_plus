# SegurPlus — Brief del proyecto (v3, simplificado)

Estado: v3 (2026-11-05), rama `simple`. Sustituye a la v2 (diseño F0 completo, conservado en la rama `f0-diseno`). Qué se recortó y por qué: [DECISIONES](DECISIONES.md).
Este fichero es el **contexto estable**. Las instrucciones de cada fase están en `docs/phases/`, las convenciones de código en `CLAUDE.md` y la política de acceso en `docs/security/permissions-matrix.md`.

## 1. Qué es y para qué sirve
SegurPlus es una empresa ficticia que comercializa seguros online (auto, hogar, vida, salud). El proyecto es un **portfolio profesional centrado en seguridad y autorización, y en RAG con permisos**; todo lo demás es mínimo: «la app lo más simple posible».

Lo que lo distingue de un chatbot RAG genérico: **un usuario nunca obtiene información que no puede leer, por ningún canal** (documentos, chat, SQL, conversaciones, descargas, API directa).

## 2. Supuestos
| ID | Supuesto | Por qué importa |
|---|---|---|
| A1 ✅ | SegurPlus es **corredor/mediador online**: su ingreso es la comisión que paga el asegurador (`policies.insurer_commission_rate`). No asume riesgo, no hay siniestros. | Define ingresos y gastos. Con los números ancla de octubre el mes sale en pérdidas; es aceptable (empresa joven). |
| A2 ✅ | Fecha ficticia de «hoy»: **2027-01-15**. Datos de septiembre a diciembre de 2026. `Clock` inyectable; la fecha se pasa al asistente. | Sin esto «este mes» se resolvería con la fecha real. |
| A3 *(reformulado)* | Hay **un solo asistente**; el backend deriva del rol del JWT qué KBs y herramientas usa. 4 KBs de Dify. (Antes: 4 asistentes y 9 KBs.) | Simplicidad. |
| A4 ✅ | Objetivos de octubre ajustados a la escala de las ventas (§6). | «Objetivos alcanzados» necesita variedad. |
| A5 ✅ | Contabilidad ve las nóminas **solo agregadas** (una línea «Sueldos y salarios» en el libro mayor y el extracto). | Evita contradecir la regla «sin salarios individuales para ACC001». |
| A6 *(reformulado)* | El asistente es un **perfil de configuración en YAML/código**, no una tabla ni una app de Dify por usuario. | Sin duplicar prompts. |

## 3. Empresa y usuarios
| ID | Nombre | Departamento | Rol |
|---|---|---|---|
| DIR001 | Director General | Dirección | MANAGER |
| ACC001 | Responsable Financiero | Contabilidad | ACCOUNTANT |
| COM001 | Responsable Comercial | Comercial | SALES_MANAGER |
| EMP001 | Pedro Gómez | Comercial | EMPLOYEE |
| EMP002 | Laura Fernández | Comercial | EMPLOYEE |
| EMP003 | Martín Rodríguez | Comercial | EMPLOYEE |
| EMP004 | Sofía Martínez | Comercial | EMPLOYEE |

Todos los datos son ficticios.

## 4. Principios de seguridad (no negociables)
1. El **backend** decide toda autorización. Ni el frontend ni el LLM ni Dify (`user`) autorizan nada.
2. La identidad sale **solo del JWT**. Se ignora cualquier `employee_id`, `role`, `assistantId` o `datasetId` del cliente.
3. **Deny by default**, regla ABAC mínima: `READ`/`DOWNLOAD` si el rol está en `documents.allowed_roles` o el usuario es el propietario; solo `MANAGER` crea, actualiza, borra y administra.
4. Recurso no autorizado → **404**. Listados, conteos y facetas se filtran en la consulta.
5. **Un único servicio de política** (`AccessPolicyService`) usado por REST, herramientas de IA y recuperación RAG.
6. Cada fragmento recuperado se **re-autoriza** antes de llegar al LLM. El filtro de Dify es una optimización, no el control.
7. El LLM **no ejecuta SQL libre**: solo herramientas con parámetros tipados; `employee_id` nunca es parámetro.
8. Archivos en MinIO en bucket privado; acceso solo vía backend. Descargas auditadas.
9. Contenido de documentos = **dato no confiable** (prompt injection). El LLM no tiene herramientas de escritura.
10. Auditoría con **metadatos**, no con contenido. Nunca registrar JWT, prompts ni fragmentos.
11. La clave de Dify (y la de Gemini) solo existe en el backend.

## 5. Arquitectura
```
React + TS  →  Spring Boot (5 paquetes)  →  PostgreSQL (11 tablas: datos, metadatos, auditoría)
                     │                    →  MinIO (originales)
                     └── AiGateway (puerto) →  Dify (Knowledge API + chat-messages)  →  Gemini
```
- Paquetes por funcionalidad: `auth`, `documents`, `ai`, `data` (clientes, pólizas, comisiones, finanzas, objetivos), `audit` + `shared`. Sin Spring Modulith ([ADR 0001](adr/0001-paquetes-por-funcionalidad.md)).
- `AiGateway` es un puerto con adaptador Dify, sustituible sin tocar el resto.
- **4 bases de conocimiento**: KB-General (empresa + seguros), KB-Direccion, KB-Finanzas, KB-Empleados (un dataset con metadato `owner_id`).
- **Pipeline de documentos** simplificado: subida → MinIO → metadatos en PostgreSQL → indexación en Dify con metadatos; estados `PENDING → INDEXING → INDEXED / FAILED` ([ADR 0004](adr/0004-pipeline-simplificado.md)).
- Frontend: **Login, Documentos (listado, visor PDF, preview Excel, descarga), Chat (SSE + citas) y Dashboard sencillo**.

### Flujo del chat (resumen; ver [04-chat-flow](architecture/04-chat-flow.md))
1. React envía `POST /api/v1/chat` (`{conversationId?, message}`) con JWT.
2. El backend resuelve identidad y el perfil del rol (KBs, filtro de metadatos, herramientas).
3. Una llamada de planificación propone herramientas y recuperación; el backend valida el plan contra el catálogo.
4. Recuperación en Dify con filtro de metadatos **y re-autorización de cada fragmento**; herramientas SQL con alcance del JWT.
5. El contexto autorizado, delimitado y no confiable, pasa a la generación (`chat-messages`).
6. Respuesta por streaming SSE con citas autorizadas; evento final `verification`.
7. Se guarda el mensaje (fuentes, herramientas, tokens, latencia) y la auditoría.

### Dify: detalles de integración
- Dos claves distintas: app (`/chat-messages`) y Knowledge (`/datasets/...`). Los parámetros de `retrieve` y los operadores de filtro de metadatos de 1.18 **no están verificados**; se comprueban en el Swagger de la instancia en F3.
- `user` de Dify: opaco y estable (`u_<uuid>`). Aísla historiales; no autoriza.
- `conversation_id` de Dify en `conversations.dify_conversation_id`; la tabla propia es la fuente de verdad.
- Citas: `metadata.retriever_resources` → `documents.id` y comprobación de READ antes de enviarlas.
- Métricas: `metadata.usage` → tokens y latencia por mensaje. Sin precios ni cuotas.
- Si el stream solo emite `ping`: revisar el worker de Dify.
- Reranking y búsqueda híbrida: mejora opcional.

## 6. Datos coherentes
- **PostgreSQL es la fuente de verdad.** Un generador determinista (semilla fija) produce los 29 documentos (PDF, XLSX, CSV, JSON) y 3 generales desde la BD. Contratos y nóminas se generan desde `users` (con su salario mensual); no hay tablas de RR. HH.
- **Mes ancla: octubre de 2026.** Septiembre, noviembre y diciembre se generan alrededor (altas, renovaciones y cancelaciones).

| Empleado | Ventas | Primas | Comisión 10 % | Objetivo | Cumplimiento |
|---|---:|---:|---:|---:|---:|
| EMP001 | 3 | 3.150 € | 315 € | 3.000 € | 105,0 % |
| EMP002 | 3 | 2.280 € | 228 € | 3.000 € | 76,0 % |
| EMP003 | 3 | 2.050 € | 205 € | 2.800 € | 73,2 % |
| EMP004 | 3 | 2.200 € | 220 € | 3.200 € | 68,8 % |
| **Total** | **12** | **9.680 €** | **968 €** | **12.000 €** | **80,7 %** |

- Gastos de octubre: 7.250 € (sin nóminas ni comisiones), desglosados por categoría en `expenses`.
- Relaciones: póliza→empleado y cliente (la venta es la póliza); comisión→empleado y póliza; gasto→factura opcional y cuenta contable por columna.
- **Imperfecciones deliberadas:** partidas sin conciliar, dos versiones del mismo informe como documentos distintos, diferencia de redondeo, pares casi idénticos con distinta clasificación, un documento con prompt injection (fuera del corpus principal). Las preguntas de evaluación van **separadas** del corpus.
- Un fallo de permisos nunca debe deberse a un defecto del dataset.

## 7. RAG frente a SQL
- **RAG:** contratos, manuales, actas, estrategia, informes, gastos y facturas (desde sus documentos), explicación de coberturas.
- **SQL (4 herramientas tipadas):** `get_sales_summary`, `get_commissions`, `get_policies_by_status`, `get_targets`, más `search_documents` para RAG. Los números de ventas, comisiones y objetivos salen de la BD, nunca de fragmentos de Excel/CSV.
- Cada cifra de una respuesta debe aparecer en la salida de una herramienta (señal de calidad en el evento `verification`).

## 8. Criterios de aceptación (medibles)
1. **Matriz de acceso al 100 %** (usuario × documento × acción, generada de la política) en REST, listados, recuperación RAG, citas, herramientas SQL y descargas.
2. **Ningún acceso cruzado**: EMP↔EMP, ACC↔Dirección, COM↔Dirección/nóminas, conversaciones ajenas; incluye el corpus de prompt injection.
3. **Identidad solo por JWT**: rol, identidad, asistente o KB enviados por el cliente no cambian el resultado.
4. **La clave de Dify no aparece en el frontend** (bundle y tráfico de red).
5. **PDF visualizable, Excel previsualizable y descarga** autorizada y auditada; no autorizado ⇒ 404.
6. **Recall@5 ≥ 0,8 y 100 % de citas válidas** sobre el conjunto de ~30 preguntas.
7. **Consistencia**: cada cifra de cada documento coincide con la calculada en BD (y con el dashboard/chat).
8. **`docker compose up` + seed determinista reproduce todo** (7 logins, dashboards, 32 documentos); la CI ejecuta los tests.

## 9. Modo de trabajo
1. Revisar críticamente; evitar complejidad que no sirva al objetivo del portfolio.
2. Cambios pequeños y verificables; cada funcionalidad con sus tests, **incluidos los de autorización**.
3. Decisiones importantes como ADR en `docs/adr/` en el momento de tomarlas.
4. Preguntar cuando una decisión sea del propietario; no asumir en silencio.

## 10. Preguntas abiertas
Ver [DECISIONES · pendientes para el propietario](DECISIONES.md#pendientes-para-el-propietario).
