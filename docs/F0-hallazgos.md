# F0 — Hallazgos: contradicciones, huecos y decisiones pendientes

Regla aplicada: no se corrige en silencio ni el brief ni la matriz. Cada punto indica qué se hizo **provisionalmente** en el diseño y qué decide el propietario. Ningún hallazgo bloquea F1, por eso no se lanzó `ask_parent`.

Leyenda de estado: **CONFIRMAR** = se aplicó una propuesta por defecto y hay que validarla · **ABIERTO** = falta una decisión o dato · **INFO** = resuelto en el diseño, solo para que conste.

## Resumen
| ID | Tema | Dónde | Estado | Gravedad |
|---|---|---|---|---|
| H-01 | IDs de «actividad comercial» incoherentes dentro de la matriz | matriz | **CONFIRMAR** | Alta (seguridad) |
| H-02 | Escala: 12 pólizas en octubre frente a 150-300 en total | brief §6 | **ABIERTO** | Media |
| H-03 | «Nivel del sujeto» no está definido | matriz regla 2 | CONFIRMAR | Alta |
| H-04 | Alcance de la regla de propietario | matriz regla 2 | CONFIRMAR | Alta |
| H-05 | CREATE/UPDATE/DELETE/ADMINISTER: «su ámbito» sin definir; CREATE no es un grant por documento | matriz regla 3 | CONFIRMAR | Media |
| H-06 | COM001 puede leer D11 pero su asistente no tiene la KB Finance | matriz | **ABIERTO** | Media |
| H-07 | La actividad comercial vive en KB-EMPxxx, no en KB-Sales | matriz | CONFIRMAR | Media |
| H-08 | READ sin DOWNLOAD frente a un visor PDF/Excel | matriz + brief CA-5 | CONFIRMAR | Media |
| H-09 | «Vistas con alcance» no pueden conocer al usuario | brief §5 | CONFIRMAR | Media |
| H-10 | Paso 3 del flujo: ¿qué LLM propone herramientas? | brief §5 | CONFIRMAR | Media |
| H-11 | Paso 6 (verificar) frente a paso 8 (streaming) | brief §5 | **ABIERTO** | Alta |
| H-12 | `NUMERIC(14,2)` no sirve para costes de IA por mensaje | CLAUDE.md/brief | **CONFIRMAR** | Media |
| H-13 | Se guarda el contenido del chat; revocaciones posteriores | brief §4.10 | **ABIERTO** | Alta (privacidad) |
| H-14 | Catálogo inicial de herramientas incompleto | brief §7 | CONFIRMAR | Media |
| H-15 | Permisos de datos y de conversaciones ajenas sin definir | matriz | **CONFIRMAR** | Alta |
| H-16 | Los 7.250 € de gastos: ¿base o con IVA? | brief §6 | CONFIRMAR | Baja |
| H-17 | «Factura→operación» en un corredor | brief §6 | CONFIRMAR | Media |
| H-18 | Las «imperfecciones deliberadas» no figuran en la matriz ni en el recuento de 29 | brief §6 | ABIERTO (F2) | Baja |
| H-19 | A2 obliga a dos relojes | brief A2 | INFO | — |
| H-20 | A1: el mes sale en pérdidas por mucho más que lo indicado | brief A1 | ABIERTO | Baja |
| H-21 | Usuarios con varios roles | brief | CONFIRMAR | Baja |
| H-22 | API de Dify 1.18 no verificada (filtros de metadatos, `retrieve`, actualización) | brief §5 | ABIERTO (F4) | Media |
| H-23 | Umbrales de las reglas de contexto | matriz regla 4 | ABIERTO | Baja |
| H-24 | KB-Sales contiene un único documento | matriz | INFO | Baja |
| H-25 | Grants a sujetos sin clearance suficiente son inertes | matriz | CONFIRMAR | Baja |
| H-26 | Fuentes de los empleados (pregunta abierta §10) | brief §10 | CONFIRMAR | Baja |
| H-27 | Nómina propia por IA / SQL | matriz | CONFIRMAR | Media |
| H-28 | Texto original de §18 (puntos 5 en adelante) inferido | brief §9-10 | INFO | — |
| H-29 | «Modulith o ArchUnit» | brief §5 / CLAUDE.md | INFO (ADR 0001) | — |
| H-30 | `/api/chat` frente a `/api/v1` | brief §5 / CLAUDE.md | CONFIRMAR | Baja |
| H-31 | Ventas y cancelaciones: la venta no cambia de estado | modelo | CONFIRMAR | Media |
| H-32 | Desnormalizaciones que pide el diseño (`dify_dataset_id`, `checksum`) | F0-diseno.md | CONFIRMAR | Baja |
| H-33 | Tarifas de modelo de IA no verificadas | modelo | ABIERTO | Baja |

---
## Detalle

### H-01 · IDs de actividad comercial incoherentes (matriz)
La tabla de documentos agrupa D14-16 como «contrato / actividad comercial / nómina y comisiones» (el orden de columnas implica **D14 contrato, D15 actividad, D16 nómina**; igual en D17-19, D20-22, D23-25). Pero la tabla «Asistentes y fuentes» dice que Sales usa «actividad comercial de empleados (**D16, D19, D22, D25**)», que según la primera tabla serían las **nóminas** (RESTRICTED, con salario). Los casos negativos citan «EMP001 → D17/D18/D19» como documentos de EMP002, coherente con la primera tabla.
- Aplicado: actividad = D15, D18, D21, D24 (orden de columnas, nota «solo actividad: R» y principio de no exponer salarios a COM001). Es además la lectura que **no** concede acceso a nóminas si me equivoco.
- Decide el propietario: confirmar la numeración o corregir la matriz. Impacto si fuera al revés: el grant `SALES_MANAGER READ` de [permissions-spec.yaml](data-model/permissions-spec.yaml) iría a otros cuatro documentos.

### H-02 · Escala del dataset (brief §6)
Octubre tiene **12 ventas/pólizas**, pero se habla de «≈150-300 pólizas en total» en septiembre-diciembre, con altas, renovaciones y cancelaciones. A ~12 altas/mes salen ≈48 altas en cuatro meses.
- Propuesta: los 150-300 incluyen la **cartera previa** (pólizas con alta anterior a septiembre) y sus renovaciones; las altas nuevas por mes quedan cerca de las de octubre. El modelo lo permite (`policies.start_date` anterior; `sales` solo para altas).
- Decide el propietario: si el objetivo es 150-300 **altas**, habría que escalar ventas y objetivos (y A4 cambia).

### H-03 · Nivel (clearance) del sujeto
La regla 2 compara la clasificación del recurso con «el nivel del sujeto», que no está definido en ningún sitio.
- Aplicado: `roles.clearance_level` — MANAGER=RESTRICTED, ACCOUNTANT=CONFIDENTIAL, SALES_MANAGER=CONFIDENTIAL, EMPLOYEE=INTERNAL (todas las celdas de la matriz son coherentes con esto; comprobado).
- Consecuencia: el empleado accede a RESTRICTED (contrato, nómina) solo por la regla de propietario (H-04).

### H-04 · Alcance de la regla de propietario
«…o el sujeto es el propietario» no dice para qué acciones ni si salta la clasificación.
- Aplicado: da **READ y DOWNLOAD** (nada más) y salta la clasificación. No se materializa en `document_permissions`: se deriva de `documents.owner_employee_id`. Los documentos corporativos tienen `owner_employee_id = NULL` (el autor va en `created_by_user_id`).

### H-05 · CREATE/UPDATE/DELETE/ADMINISTER
La regla 3 dice «según rol (a definir en F1; por defecto solo MANAGER sobre su ámbito)». «Su ámbito» es ambiguo y CREATE no puede ser un grant sobre un documento que aún no existe.
- Aplicado: se expresan en `role_permissions` (`DOCUMENT:<acción>:GLOBAL`); por defecto solo MANAGER, sobre todos los documentos. En `document_permissions` no se siembran filas CREATE.
- Hay que decidir si Contabilidad puede crear/actualizar en KB-Finance (naturalmente sí en la vida real) y si el responsable comercial puede actualizar D06. La matriz actual dice que no.

### H-06 · D11 para COM001 frente al asistente Sales
COM001 tiene READ sobre D11 (liquidación de comisiones de todos), pero las fuentes del asistente Sales son Company, Insurance, Sales y la actividad: **no** incluyen KB-Finance. Por REST puede leerlo; por chat no lo recuperará. La matriz exige «una misma regla en REST, IA y RAG».
- Opciones: (a) añadir D11 como fuente del asistente `sales` con filtro por documento; (b) aceptar que el chat sea una vista más estrecha que REST (las KB son cota superior, no concesión). Hoy el diseño aplica (b) y las cifras de comisiones del equipo salen por SQL (`get_commissions`, alcance TEAM), que cubre el caso de uso.

### H-07 · Actividad comercial en KB-EMPxxx
Los documentos D15/18/21/24 pertenecen a la KB de cada empleado (con contrato y nómina). El asistente Sales necesita fragmentos de esos datasets sin tocar contrato ni nómina.
- Aplicado: `ai_assistant_knowledge_bases` con selector `ALL_EMPLOYEE_KBS` **+ categoría** `COMMERCIAL_ACTIVITY`, y re-autorización por fragmento (los contratos darían DENY).
- Alternativa más simple: mover la actividad comercial a KB-Sales (la KB tiene un solo documento, H-24). Cambiaría la columna «KB» de la matriz; propuesta para F2.

### H-08 · READ sin DOWNLOAD frente al visor
COM001 solo tiene READ sobre D15/18/21/24 y D11. Para mostrar un PDF en pdf.js el navegador recibe el fichero entero; no se puede impedir que lo guarde.
- Aplicado: «visualizar» = entrega inline (`Content-Disposition: inline`, `no-store`) bajo READ; «descargar» = DOWNLOAD, auditada y con controles de contexto. Se audita la lectura inline. Se advierte: DOWNLOAD es un control de **política y trazabilidad**, no una barrera técnica contra copiar lo visto. Alternativa más estricta: renderizar páginas a imagen en servidor para los documentos «solo lectura» (como ya se hace con Excel, CA-5).

### H-09 · «Vistas con alcance» (brief §5, paso 4)
Una vista SQL no conoce al usuario sin variables de sesión. 
- Aplicado (ADR 0005, 0002): el alcance se aplica en Java (`Specification`/servicios de `analytics`); solo hay una vista real, sin dependencia del usuario (`v_payroll_monthly_aggregate`). RLS de PostgreSQL queda como defensa en profundidad opcional en F8.

### H-10 · Paso 3: «El LLM propone herramientas»
El brief no dice qué llamada hace la propuesta. Dentro de un chatflow de Dify, las herramientas se ejecutan en Dify (fuera del control del backend).
- Aplicado (ADR 0003): llamada de planificación a una app de Dify sin conocimiento ni herramientas, con salida JSON estricta validada por el backend; luego generación con contexto autorizado. Cuesta una llamada más por turno (tokens sumados en `messages`).

### H-11 · Verificar antes de entregar vs. streaming
El paso 6 verifica cifras y citas; el paso 8 hace streaming SSE. Si se verifica después, ya se emitió lo erróneo (el mismo problema de la Variante A).
- Propuesta (04-chat-flow): respuestas con cifras de SQL ⇒ generación bloqueante, verificación y emisión por SSE; respuestas solo documentales ⇒ streaming real con evento final de verificación (el LLM solo vio texto autorizado). **Decide el propietario** el compromiso latencia/garantía.

### H-12 · Costes de IA y `NUMERIC(14,2)`
Un mensaje cuesta del orden de 0,0003 €; con 2 decimales se redondea a 0 y la cuota/coste diario sería falsa (se comprobó en PostgreSQL: `0,000312` se conserva en `NUMERIC(14,6)`).
- Aplicado: excepción acotada a `messages.price_amount`, `ai_model_prices.*_price_per_mtok` (`NUMERIC(14,6)`). Resto de importes `NUMERIC(14,2)`. CLAUDE.md dice «importes NUMERIC(14,2)»: requiere aprobación explícita para esta excepción.

### H-13 · Contenido del chat y revocaciones
El brief pide auditar «sin contenido» y no registrar prompts/respuestas, pero el historial de conversaciones necesita el texto (`messages.content`).
- Aplicado: el contenido solo vive en `messages` (propietario únicamente, ajena ⇒ 404); jamás en `audit_logs` ni en logs.
- **Abierto**: (1) retención y borrado a petición del usuario; (2) si el texto de una respuesta citó un documento cuyo grant se revoca después, el texto de la respuesta sigue en el historial: el diseño oculta las **fuentes** no autorizadas al releer, pero no puede «des-decir» la respuesta. Opciones: aceptar, o invalidar/ocultar mensajes cuyas fuentes ya no se pueden leer.

### H-14 · Catálogo de herramientas
El catálogo inicial (`get_sales_summary`, `get_commissions`, `get_policies`, `compare_periods`, `get_objectives`, `search_documents`) no cubre «facturación, gastos, clientes» (§7), necesarias para Contabilidad. `search_documents` es RAG, no SQL.
- Propuesta (ADR 0005): añadir `get_expenses`, `get_invoices`, `get_accounting_summary`, `get_customers`.

### H-15 · Permisos de datos y conversaciones ajenas
La matriz solo cubre documentos y asistentes. Faltan: clientes, leads, pólizas, evaluaciones de desempeño, ausencias, usuarios, auditoría; y si MANAGER puede leer conversaciones ajenas (no se dice; por defecto **no**).
- Propuesta: tabla de [permissions-mapping §5](data-model/permissions-mapping.md). Debe confirmarse antes de F1.

### H-16 · Gastos 7.250 €
Se desconoce si es base o total con IVA. Como corredor, la intermediación está exenta de IVA y el IVA soportado puede no ser deducible.
- Aplicado: la cifra ancla se aplica a `expenses.total_amount`; `amount` + `tax_amount` = total. Confirmar.

### H-17 · «Factura→operación»
En un corredor no se factura la prima al cliente (la cobra el asegurador).
- Aplicado: facturas **emitidas** a la aseguradora (liquidación de comisiones, `insurer_commissions.invoice_id`) y **recibidas** de proveedores (`expenses.invoice_id`). La «operación» es la comisión liquidada o el gasto. Confirmar que no se necesita facturar primas.

### H-18 · Imperfecciones deliberadas
«Dos versiones del mismo informe» y «pares casi idénticos con distinta clasificación» no aparecen en la matriz ni en el recuento (29+3). El modelo los soporta (`document_versions`; dos documentos con la misma KB y distinta `classification`), pero F2 debe fijar cuáles son, y la matriz debe incluirlos (o declararlos fuera del corpus).

### H-19 · Dos relojes
Fijar «hoy» en 2027-01-15 no puede congelar también los JWT ni los reintentos: ver ADR 0006 (reloj de negocio y de sistema).

### H-20 · A1: el mes sale en pérdidas
Con 9.680 € de primas, aun con una tasa del asegurador del 15-20 % el ingreso de octubre (~1.450-1.940 €) es inferior a los gastos (7.250 €) **más** las comisiones (968 €) **más** las nóminas. No hay tasa razonable que equilibre el mes con ese volumen. El brief ya lo admite («aceptable o se escala»); se señala la magnitud para que los informes de Dirección (D01, D13) sean coherentes: cualquier narrativa de «beneficio» sería falsa. Comisiones de renovación de la cartera previa (H-02) amortiguan algo, no equilibran.

### H-21 · Varios roles por usuario
`user_roles` lo permite, pero el asistente se resuelve por rol (`ai_assistants.role_id` único) y el alcance de herramientas por rol. v1: **un rol por usuario**; si hiciera falta más, hay que definir precedencia.

### H-22 · API de Dify no verificada
No se ha comprobado el Swagger de la instancia 1.18: parámetros exactos de `retrieve`, operadores de los filtros de metadatos (¿hay «in»?, ¿`and`/`or`?), actualización por fichero y por metadatos, y estado de indexación. Hay un contenedor `dify-api:1.18.0` en la máquina, pero no se accedió a su API en F0 (fuera de alcance). El diseño tolera operadores limitados (filtra por dataset y re-autoriza).

### H-23 · Umbrales de contexto
La regla 4 (descargas masivas, intentos fallidos, horario) no da umbrales. Propuesta: parámetros de configuración (p. ej. N descargas/10 min), alimentados desde `audit_logs` (`ix_audit_logs_downloads`).

### H-24 · KB-Sales con un solo documento
KB-Sales contiene solo D06 (7 documentos en Finance, 5 en Management). Sin problema técnico; el recall@5 sobre esa KB tendrá poco contraste.

### H-25 · Grants inertes
Un grant a un sujeto cuyo clearance es menor que la clasificación no concede nada (salvo propietario). En la siembra no hay ninguno (comprobado). La alta de grants debe advertirlo; test de sensibilidad en permissions-mapping §6.

### H-26 · Fuentes de los empleados
La pregunta abierta de §10 («Company + Insurance además de su KB») ya está recogida por la matriz; se cierra con esa propuesta.

### H-27 · Nómina propia por IA / SQL
La matriz dice que el empleado ve sus comisiones «en su nómina y vía SQL», y «solo datos propios» para el asistente. No hay herramienta de nóminas (ni siquiera para Management): la nómina se consulta por documento (propietario) o REST. Confirmar.

### H-28 · §18 original truncado
Los puntos 5-8 del modo de trabajo están inferidos. Sin impacto en el diseño.

### H-29 · Modulith o ArchUnit
Resuelto en ADR 0001: Modulith para módulos y eventos, ArchUnit para reglas adicionales.

### H-30 · `/api/chat` frente a `/api/v1`
El brief dice `POST /api/chat`; CLAUDE.md fija «API bajo `/api/v1`». Aplicado: `POST /api/v1/chat`.

### H-31 · Cancelaciones y ventas
Aplicado: una venta confirmada **no cambia de estado** al cancelarse la póliza; la cancelación vive en `policy_cancellations` y revierte la comisión (`commissions.status='REVERSED'`, `insurer_commissions` con `clawback_of_id`). Así los cierres mensuales (y los números ancla de octubre) no se reescriben. Alternativa: ventas «netas» de cancelaciones; cambia la definición de cumplimiento de objetivos.

### H-32 · Desnormalizaciones
`documents.dify_dataset_id` y `documents.checksum_sha256` están pedidos por F0-diseno.md pero duplican `knowledge_bases` y `document_versions`. Se protegen con FK compuesta `(knowledge_base_id, dify_dataset_id)` y con una invariante probada para el checksum.

### H-33 · Tarifas de IA
`ai_model_prices` es configuración; los precios reales de Gemini no se verificaron en F0 y deben cargarse al sembrar.

---
## Qué se verificó como coherente (sin hallazgo)
- 29 documentos de la matriz (D01-D29) + 3 generales = 32; 9 KBs (Management, Sales, Finance, Insurance, Company, EMP001-004) cuadran con los documentos y con los asistentes.
- Todas las celdas de la matriz cumplen la regla 2 con los clearance de H-03.
- Números ancla: 12 ventas, 9.680 €, 968 € (10 %), 12.000 € de objetivo, 80,7 % y los cumplimientos por empleado, 7.250 € de gastos — todos representables (y comprobados con datos de prueba en PostgreSQL 16, ver el informe).

---
## Validación realizada en F0 (y límites)
Verificado con herramientas locales (scripts auxiliares **no versionados**; el repo no contiene código en F0):
- **DDL real**: las 54 `CREATE TABLE` y 150 índices de [schema.md](data-model/schema.md) se ejecutaron sin errores en una instancia temporal de PostgreSQL 16 (con `btree_gist`), con las FK circulares añadidas después con `ALTER TABLE ... DEFERRABLE`. Se probaron los CHECK/UNIQUE/EXCLUDE clave (comisión = `round(base*tasa,2)`, gasto no `DRAFT` ⇒ asiento, categoría `PAYROLL` prohibida, factura con contraparte coherente, grant duplicado, FK compuesta KB↔dataset, mensaje de usuario sin tokens, solape de contratos, partición de auditoría inexistente ⇒ error).
- **Números ancla de octubre** con datos de prueba: 12 ventas, 12 pólizas, 9.680,00 € de primas, 968,00 € de comisiones, 7.250,00 € de gastos (6 categorías, cada gasto con su asiento equilibrado), 12.000,00 € de objetivos y cumplimientos 105,0 / 76,0 / 73,2 / 68,8 % (80,7 % total).
- **Coherencia**: cada FK referencia una tabla y columna existentes y única; cada FK tiene índice; los importes son `NUMERIC(14,2)` (excepciones declaradas en H-12) y todas las tablas con importes tienen moneda; ERD generado desde el esquema; todos los H-, RF-, RNF-, UC-, NEG- referenciados están definidos; sin enlaces relativos rotos; el grafo de módulos es acíclico y contiene exactamente los 14 módulos.
- **Matriz**: `permissions-spec.yaml` se expandió con el oráculo de la sección 1 de permissions-mapping (127 grants, 1.344 celdas, 321 ALLOW; los 12 casos negativos probados dan DENY; ningún grant inerte).

**No verificado:**
- Sintaxis **Mermaid**: no hay `mmdc` ni biblioteca Mermaid en la máquina y no se instaló nada. Solo hay un lint estructural propio (tipo de diagrama, llaves/corchetes/comillas, bloques `end`, entidades declaradas); se evitaron `;` y `#` y se mantuvo la sintaxis simple. Conviene abrir los `.md` en un visor con Mermaid (GitHub, VS Code) antes de darlos por buenos.
- API de Dify 1.18 (H-22), tarifas de Gemini (H-33), rendimiento de índices con volumen real (el dataset es pequeño), triggers descritos pero no escritos (equilibrio de asientos, validación de `subject_id`, append-only de auditoría), `NULLS NOT DISTINCT` en versiones de PostgreSQL < 15.
- La conformidad de la matriz Markdown con `permissions-spec.yaml` se revisó a mano (códigos y celdas), sin un parser automático de la tabla Markdown.
