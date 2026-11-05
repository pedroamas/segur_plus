# SegurPlus — Brief del proyecto (v2)

Estado: borrador v2 (2026-11-05). Sustituye a `descripcion_proyecto.md`.
Este fichero es el **contexto estable**. Las instrucciones de cada fase están en `docs/phases/`, las convenciones de código en `CLAUDE.md` y la política de acceso en `docs/security/permissions-matrix.md`.

## 1. Qué es y para qué sirve
SegurPlus es una empresa ficticia que comercializa seguros online (auto, hogar, vida, salud). El proyecto es un **portfolio profesional** que demuestra: arquitectura backend (Java 21 / Spring Boot), autorización real, RAG con permisos, gestión documental, analítica sobre datos coherentes y evaluación de calidad de IA.

Lo que lo distingue de un chatbot RAG genérico: **un usuario nunca obtiene información que no puede leer, por ningún canal** (documentos, chat, SQL, conversaciones, descargas, API directa).

## 2. Supuestos a confirmar (cambiar aquí si no son correctos)
| ID | Supuesto | Por qué importa |
|---|---|---|
| A1 ✅ confirmado | SegurPlus es **corredor/mediador online**: su ingreso es la comisión que paga el asegurador (parámetro `insurer_commission_rate`, a calibrar). No asume riesgo, no hay siniestros ni provisiones. | Define libro mayor, facturas y "ingresos/beneficio". Con los números ancla de octubre el mes sale en pérdidas; es aceptable (empresa joven) o se escala el volumen de los otros meses. |
| A2 ✅ confirmado | Fecha ficticia de "hoy": **2027-01-15**. Datos de septiembre a diciembre de 2026. Reloj inyectable (`Clock`) y la fecha se pasa al asistente. | Diciembre de 2026 es futuro real; sin esto "este mes" se resolvería con la fecha real del LLM. |
| A3 ✅ confirmado | El responsable comercial (COM001) tiene **Sales Assistant** (4.º tipo) y una **KB-Sales** nueva (9 KBs en total). | COM001 no tenía asistente ni documentos propios. |
| A4 ✅ confirmado | Objetivos de octubre **ajustados** a la escala de las ventas (ver §6). El original (32.000 €) daba 30 % de cumplimiento y nadie alcanzaba objetivo. | "Objetivos alcanzados" necesita variedad. |
| A5 ✅ confirmado | Contabilidad ve las nóminas **solo agregadas** (una línea "Sueldos y salarios" en libro mayor, extracto y presupuesto). | Evita que el propio dataset contradiga la regla "sin salarios de directivos". |
| A6 ✅ confirmado | Un asistente es un **perfil de configuración en BD** (`ai_assistants`), no una app de Dify por usuario. | Sin duplicar prompts; el backend decide fuentes y herramientas. |

## 3. Empresa y usuarios
| ID | Nombre | Departamento | Rol | Asistente |
|---|---|---|---|---|
| DIR001 | Director General | Dirección | MANAGER | Management |
| ACC001 | Responsable Financiero | Contabilidad | ACCOUNTANT | Accounting |
| COM001 | Responsable Comercial | Comercial | SALES_MANAGER | Sales (A3) |
| EMP001 | Pedro Gómez | Comercial | EMPLOYEE | Employee |
| EMP002 | Laura Fernández | Comercial | EMPLOYEE | Employee |
| EMP003 | Martín Rodríguez | Comercial | EMPLOYEE | Employee |
| EMP004 | Sofía Martínez | Comercial | EMPLOYEE | Employee |

Todos los datos son ficticios.

## 4. Principios de seguridad (no negociables)
1. El **backend** decide toda autorización. Ni el frontend ni el LLM ni Dify (`user`) autorizan nada.
2. La identidad sale **solo del JWT**. Se ignora cualquier `employee_id`, `role`, `assistantId` o `datasetId` enviado por el cliente.
3. Política **deny by default**, ABAC: rol + departamento + propietario + clasificación + acción + contexto. Acciones: READ, CREATE, UPDATE, DELETE, DOWNLOAD, ADMINISTER.
4. Recurso no autorizado → **404** (no revelar existencia). Listados, conteos y facetas se filtran en la consulta.
5. **Un único servicio de política** (`AccessPolicyService`) usado por REST, herramientas de IA y recuperación RAG.
6. Cada fragmento recuperado se **re-autoriza** antes de llegar al LLM. El filtro de Dify es una optimización, no el control.
7. El LLM **no ejecuta SQL libre**: solo herramientas con parámetros tipados; el `employee_id` nunca es parámetro.
8. Archivos en MinIO en bucket privado; acceso solo vía backend. Descargas auditadas.
9. Contenido de documentos = **dato no confiable** (prompt injection). Sin herramientas de escritura para el LLM.
10. Auditoría con **metadatos**, no con contenido de preguntas o respuestas. Nunca registrar JWT, prompts ni fragmentos.
11. JWT de acceso corto (10-15 min) + refresh con rotación en cookie HttpOnly. Los roles se leen de BD/caché corta.
12. Las claves de Dify (app y Knowledge) y de Gemini solo existen en el backend.

## 5. Arquitectura
```
React + TS  →  Spring Boot (monolito modular)  →  PostgreSQL (datos, metadatos, permisos, auditoría)
                     │                          →  MinIO (originales)
                     └── AiGateway (puerto) ──→  Dify (Knowledge API + chat-messages)  →  Gemini
```
- Monolito modular verificado con **Spring Modulith** (o ArchUnit). Módulos: `auth, users, departments, documents, security, ai, analytics, sales, customers, insurance, finance, hr, audit, shared`. (`policies` renombrado a `insurance` para no confundirlo con políticas de autorización; `hr` añadido: nóminas, contratos, objetivos.)
- `AiGateway` es un puerto con adaptador Dify, para poder sustituirlo (p. ej. Spring AI + pgvector) sin tocar el resto.
- **Pipeline de documentos** (tres almacenes): subida → MinIO → metadatos en Postgres → indexación en Dify con metadatos. Estados `PENDING → INDEXING → INDEXED / FAILED`, checksum SHA-256, reindexado, y propagación de borrados y cambios de permisos hacia Dify. Decisión a documentar en un ADR.

### Flujo del chat
1. React envía `POST /api/chat` (`{conversationId?, message}`) con JWT.
2. Spring Security valida el JWT; el backend resuelve identidad y `AssistantContext{kbIds, metadataFilter, tools, promptVars}`.
3. El LLM propone herramientas (RAG, SQL o ambas).
4. El backend autoriza y ejecuta: RAG por Knowledge API de Dify con filtros de metadatos + re-autorización de cada fragmento; SQL por herramientas parametrizadas sobre vistas con alcance.
5. Los resultados se pasan como contexto delimitado (no confiable) a la generación (`chat-messages`).
6. Se verifica que cifras y citas de la respuesta existen en lo recuperado.
7. Se guarda el mensaje (fuentes, herramientas, tokens, latencia) y la auditoría.
8. Respuesta por streaming SSE; React la consume con `fetch` + `ReadableStream`.

Variante A (aceptada solo si se decide explícitamente): que Dify recupere dentro del chatflow con filtros por `inputs`. Es detección, no prevención: con streaming el backend no puede retirar lo ya enviado.

### Dify: detalles de integración
- Dos claves distintas: app (`/chat-messages`) y Knowledge (`/datasets/...`). Comprobar en el Swagger de la instancia (1.18) los parámetros exactos de `retrieve` y los filtros de metadatos.
- `user` de Dify: opaco y estable (`u_<uuid>`), sin email ni nombre. Aísla historiales; no autoriza.
- `conversation_id`: generado por Dify, guardado en `conversations.dify_conversation_id`; la tabla propia es la fuente de verdad y el propietario se comprueba ahí.
- Citas: `metadata.retriever_resources` → mapear `document_id` de Dify a `documents.id` y comprobar READ antes de enviarlo.
- Costes: `metadata.usage` → persistir tokens, precio calculado por nosotros y latencia por mensaje. Cuota diaria por usuario.
- Errores/timeouts/reintentos: conexión 3 s, primera respuesta 10 s, inactividad 30-60 s, tope 120 s. Reintentar **solo antes del primer token**. Resilience4j (circuit breaker, bulkhead). Modelo de fallback configurable (Gemini devuelve 503 por alta demanda).
- Si el stream solo emite `ping`: revisar worker/Celery/Redis de Dify.

## 6. Datos coherentes
- **PostgreSQL es la fuente de verdad.** Un generador determinista (semilla fija) produce los 29 documentos (PDF, XLSX, CSV, JSON) desde la BD. Así venta, póliza, factura, comisión y gasto cuadran por construcción.
- **Mes ancla: octubre de 2026** (propuesta A4). Septiembre, noviembre y diciembre se generan alrededor con estacionalidad (≈150-300 pólizas en total, con altas, renovaciones y cancelaciones).

| Empleado | Ventas | Primas | Comisión 10 % | Objetivo | Cumplimiento |
|---|---:|---:|---:|---:|---:|
| EMP001 | 3 | 3.150 € | 315 € | 3.000 € | 105,0 % |
| EMP002 | 3 | 2.280 € | 228 € | 3.000 € | 76,0 % |
| EMP003 | 3 | 2.050 € | 205 € | 2.800 € | 73,2 % |
| EMP004 | 3 | 2.200 € | 220 € | 3.200 € | 68,8 % |
| **Total** | **12** | **9.680 €** | **968 €** | **12.000 €** | **80,7 %** |

- Gastos de octubre: 7.250 € (excluyen nóminas y comisiones; desglosar por categoría en `expenses` y enlazar cada uno con su `accounting_entry`).
- Relaciones obligatorias: venta→empleado y cliente; póliza→venta; factura→operación; comisión→empleado y venta; gasto→asiento.
- **Imperfecciones deliberadas:** partidas sin conciliar, dos versiones del mismo informe, cifras con diferencia de redondeo entre documentos, pares de documentos casi idénticos con distinta clasificación, un documento con prompt injection (fuera del corpus principal).
- Las preguntas de evaluación (golden set) van **separadas** del corpus.
- Los documentos se diseñan para **respetar el modelo de permisos** (A5). Un fallo de permisos nunca debe deberse a un defecto del dataset.
- Además de los 29, añadir 3 documentos generales para KB-Company (código de conducta, política de seguridad de la información, procedimiento de gastos y vacaciones).

## 7. RAG frente a SQL
- **RAG:** contratos, manuales de seguros, actas, estrategia, informes, comparación de condiciones, resúmenes y explicación de coberturas.
- **SQL (herramientas):** sumas, comisiones, facturación, comparaciones de periodos, objetivos, clientes, pólizas activas, gastos. Los números salen de la BD, nunca de fragmentos de Excel/CSV.
- Catálogo inicial: `get_sales_summary`, `get_commissions`, `get_policies`, `compare_periods`, `get_objectives`, `search_documents`.
- Se evalúa también el **routing** (¿RAG, SQL o ambas?) con preguntas etiquetadas.
- Cada cifra de una respuesta debe aparecer en la salida de una herramienta.

## 8. Criterios de aceptación (medibles)
1. Siete usuarios autenticados, cada uno con el dashboard de su rol.
2. **100 %** de la matriz de acceso (usuario × documento × acción, generada de la política) se cumple, incluidos chat, SQL, conversaciones y descargas.
3. Ningún test de acceso cruzado (empleado↔empleado, Contabilidad↔Dirección, COM001↔Dirección) obtiene datos de otro usuario.
4. Un usuario no puede cambiar su rol, identidad, asistente o KB por HTTP; la clave de Dify no aparece en el frontend.
5. PDF visualizable (pdf.js), Excel previsualizable (renderizado en servidor), descarga autorizada y auditada.
6. Retrieval: recall@5 ≥ 0,8 sobre el golden set; fidelidad ≥ umbral definido en F4; 100 % de respuestas documentales con cita válida y legible por el usuario.
7. Routing RAG/SQL con acierto ≥ umbral definido en F5.
8. Test automático de consistencia: cada cifra de cada documento coincide con la calculada en BD.
9. Corpus de prompt injection: ninguna exfiltración ni alteración de permisos.
10. `docker compose up` + seed determinista reproduce todo; la CI ejecuta los tests.
11. Documentación suficiente para que otro desarrollador entienda la arquitectura (README, diagramas, ADRs).

## 9. Modo de trabajo (sección 18 del original, completada)
El original quedó cortado en el punto 5 ("5. Ev…"); los puntos 5 en adelante están **inferidos** y conviene que los revises.
1. Revisar críticamente la arquitectura propuesta.
2. Identificar errores, riesgos y decisiones innecesarias.
3. Proponer mejoras justificadas.
4. Explicar las decisiones técnicas y las alternativas descartadas.
5. Evaluar cada cambio contra los principios de seguridad (§4) antes de implementarlo.
6. Trabajar en pasos pequeños y verificables; cada funcionalidad entra con sus tests, **incluidos los de autorización**.
7. Registrar las decisiones importantes como ADR en `docs/adr/` en el momento de tomarlas.
8. Preguntar cuando una decisión sea del propietario del proyecto; no asumir en silencio.

## 10. Preguntas abiertas
- (A1-A6 confirmados el 2026-11-05.)
- Fuentes de las que deben disponer los empleados además de su KB (propuesta: KB-Company + KB-Insurance).
- Texto original de §18 desde el punto 5.
