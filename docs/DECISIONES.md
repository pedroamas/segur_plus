# Decisiones de simplificación

Rama `simple`, creada desde `f0-diseno` (que queda intacta como referencia). El propietario pidió «la app lo más simple posible»: el proyecto es un portfolio de **seguridad/autorización y RAG con permisos**, y el resto debe ser mínimo.

## Qué se recortó
| Área | Antes (F0) | Ahora | Por qué |
|---|---|---|---|
| Tablas | 54 | **12**: `users`, `refresh_tokens`, `customers`, `policies`, `commissions`, `invoices`, `expenses`, `sales_targets`, `documents`, `conversations`, `messages`, `audit_logs` | El valor del portfolio no está en un modelo de CRM, RR. HH. y contabilidad completos |
| Usuarios y roles | users, roles, permissions, user_roles, role_permissions, employees, departments | Una tabla `users` con `role` (enum), `department`, `employee_code` y `monthly_salary` | 4 roles fijos |
| Ventas | leads, oportunidades, ventas, actividades, coberturas, renovaciones, cancelaciones | **La venta es la póliza** (`policies`); cancelación y renovación como columnas; comisión del asegurador como columnas | Menos entidades con los mismos números ancla |
| Finanzas | asientos, líneas, plan de cuentas, pagos | `invoices` y `expenses`; enlace contable = columna `ledger_account` | |
| RR. HH. | contratos, nóminas, evaluaciones, ausencias | Contratos y nóminas **solo como documentos** generados desde `users` | |
| Documentos | versiones, categorías, clasificaciones, KBs, grants | Una tabla `documents` con `allowed_roles[]`, `owner_id`, clasificación como enum | |
| Permisos | tabla de grants + clearance + contexto | `rol ∈ allowed_roles` o propietario; solo MANAGER escribe ([ADR 0002](adr/0002-abac-access-policy-service.md)) | Regla de una línea, oráculo de tests simple |
| IA | 4 asistentes en BD, 9 KBs, precios, evaluaciones en tablas | **1 asistente** (perfil YAML), **4 KBs**, sin precios, evaluación en ficheros | A6 reformulado |
| Herramientas | 10 | **4** + `search_documents` ([ADR 0005](adr/0005-herramientas-tipadas.md)) | |
| Backend | 14 módulos con Spring Modulith | **5 paquetes** + `shared` ([ADR 0001](adr/0001-paquetes-por-funcionalidad.md)) | |
| Frontend | 8+ pantallas | Login, Documentos, Chat, Dashboard sencillo | |
| Fases | 10 | **6** (F1-F6; ver «Cambio de orden de fases») | |
| Criterios de aceptación | 11 | **8** | |
| Operación | outbox, reconciliación, particionado de auditoría, triggers, `EXCLUDE`, antivirus, circuit breaker, cuota diaria | Fuera del diseño base (mejoras opcionales) | |

**No se tocó (núcleo de seguridad):** identidad solo del JWT; un único `AccessPolicyService` con deny by default; 404 y listados filtrados en la consulta; re-autorización de cada fragmento antes del LLM; sin SQL libre y `employee_id` nunca como parámetro; auditoría; batería de acceso cruzado; clave de Dify solo en backend; MinIO privado vía backend. Tampoco cambian el stack, A1, A2, los 7 usuarios, los 29+3 documentos ni los números ancla de octubre.

## Cambio de orden de fases (decidido por el propietario, rama `f3-frontend`)
Orden anterior: F3 Dify y chat · F4 Frontend · F5 Seguridad y portfolio. **Orden vigente:**

| Fase | Contenido | Antes |
|---|---|---|
| F3 | Frontend (chat visible pero «no disponible»; backend del dashboard) | F4 |
| F4 | Dify y chat en el backend | F3 |
| F5 | Conectar el chat del frontend (nueva, corta) | — |
| F6 | Seguridad y portfolio | F5 |

**Motivos:** (1) tener cuanto antes algo usable y demostrable por el propietario (login, dashboard, documentos con visor) sobre los datos reales de F2; (2) el frontend no depende de Dify, y Dify es la parte de mayor riesgo (API 1.18 no verificada, H-22): separarlo evita bloquear la UI; (3) al conectar el chat en una fase corta aparte (F5) se prueba el streaming sobre una UI ya estable; (4) el dashboard pasa a F3 porque sus servicios de `data` los necesita la UI primero y el chat de F4 los **reutiliza** (ADR 0005).

**Decisiones asociadas (F3):**
- El frontend se sirve por HTTPS (Caddy, `tls internal`) solo en la LAN (`https://192.168.0.28:5174`); no se expone a internet.
- Dashboard de **ACCOUNTANT** con **gastos del mes** (suma de `expenses.amount`) y sin cumplimiento por empleado. Es un agregado solo para el dashboard: **no** se añade herramienta de gastos al chat (la decisión de «sin `get_expenses`» sigue vigente). *Pendiente de confirmar por el propietario.*
- «Comisiones del mes»: el dato principal son las comisiones **devengadas** (`ACCRUAL`, 968,00 € en octubre, igual que los números ancla); los `CLAWBACK` del mes (negativos) se devuelven aparte junto con el neto. *Pendiente de confirmar por el propietario.*
- Mes por defecto del dashboard: último mes cerrado respecto al `Clock` (2026-12).
- El API de documentos no filtra por clasificación ni busca por título; la UI no lo simula.

## F3b — Subida de documentos desde el frontend (rama `f3b-subida`)
- La UI de subida es solo para **MANAGER** (el botón no se pinta para otros roles; la autoridad sigue siendo el backend, que responde 403).
- **Huecos del backend cerrados:** `GET /api/v1/users` (solo MANAGER; solo `id`, `employeeCode`, `fullName`, `role`; sin salario ni correo; 403 + `ACCESS_DENIED` para el resto) para el selector de propietario, y `GET /api/v1/documents/upload-config` (solo MANAGER; `maxBytes` y extensiones) para no duplicar en la UI los límites del servidor. El límite multipart de Spring (10 MB por archivo, 11 MB por petición) ya coincide con `segurplus.documents.max-upload-bytes` y Caddy no impone límite de cuerpo.
- Tipos admitidos por el backend: PDF, XLSX, CSV y JSON (no solo PDF/Excel); el UI los muestra.
- El cliente **no** replica las reglas de combinación (RESTRICTED + EMPLOYEE, KB Empleados sin propietario): se muestran los 422 del servidor, para no tener dos fuentes de verdad.
- Pendiente (fuera de alcance): no hay endpoint de borrado ni edición de documentos; los E2E limpian por SQL/S3 directo. Un documento subido queda `index_status = PENDING` hasta F4.

## F4 — Dify y chat (rama `f4-chat`)
- **Modelos** ([ADR 0008](adr/0008-embedding-y-modelos-gemini.md)): embedding `gemini-embedding-001` (fijado); chat `gemini-3-flash-preview` principal y `gemini-3.5-flash` de reserva por defecto, configurables con `GEMINI_MODEL` y `GEMINI_MODEL_FALLBACK`. **La cuota gratuita de la clave de Gemini es de 20 peticiones/día y 5/min por modelo** (error 429 `generate_content_free_tier_requests`), insuficiente para una evaluación; la corrida de F4 se hizo con `gemini-3.1-flash-lite` (principal) y `gemini-3.5-flash-lite` (reserva), que no se agotaron. Con una clave de pago bastaría cambiar las dos variables y reejecutar `deploy/dify/provision_dify.py`.
- **Cuatro apps de Dify** (planificador y generador, cada una con su modelo de reserva) porque una app está atada a un modelo; el reintento antes del primer token va a la app de reserva.
- **Ingesta por texto**: Dify no admite JSON en `create-by-file`; el backend extrae el texto (PDFBox, POI, CSV, JSON) y usa `create-by-text`.
- **Sin `conversation_id` de Dify**: el historial lo reenvía el backend desde `messages` (sin fragmentos), para que un cambio de permisos no deje contexto antiguo en Dify.
- **Una columna nueva** `messages.verification` (migración V6): el resultado de la verificación de cifras y citas se guarda en el mensaje, como decía 04-chat-flow. No hay tablas nuevas.
- **Herramientas**: reutilizan `SalesQuery`, `CommissionQuery`, `PolicyQuery` y `TargetQuery`; como esos servicios devuelven totales, **no hay desglose por empleado** (UC-A22 «comisión de cada comercial» se evalúa como «comisiones del equipo»). *Pendiente de decisión del propietario*: ampliar `data` con un desglose por empleado (mismo `DataScope`).
- **Instancia de Dify**: se alineó `WEAVIATE_AUTHENTICATION_APIKEY_ALLOWED_KEYS` con la `WEAVIATE_API_KEY` ya rotada (la rotación estaba a medias y Weaviate daba 401 al indexar); copia previa en `~/dify/docker/.env.bak-f4`. No se cambiaron puertos ni otras claves ni se actualizó Dify.

## Consecuencias del recorte a tener presentes
- **READ y DOWNLOAD van unidos** (decidido). SALES_MANAGER puede leer y descargar D11 y la actividad comercial (D15, D18, D21, D24).
- **Sin herramienta de gastos ni facturas** (decidido: no se añade `get_expenses`). Esas preguntas se responden desde los documentos D07, D12 y D13 (RAG), no desde la BD.
- **Un único dataset de empleados en Dify.** El aislamiento entre empleados lo garantiza la re-autorización; el filtro `owner_id` en Dify solo mejora el recall (ADR 0003).
- **Sin revocación por usuario individual ni caducidad**: solo por rol y propietario.
- **Sesión con refresh token rotativo** (decidido; [ADR 0007](adr/0007-sesiones-jwt-refresh-rotativo.md)): una tabla más, `refresh_tokens`.
- **Importes únicos** en `expenses` e `invoices` (columna `amount`, sin desglose de impuestos).
- **Historial de chat**: se guarda sin caducidad y sin job de retención (decisión por defecto, reversible).
- **El caso de prueba «COM001 no descarga D15»** no existe; la matriz tiene controles positivos de READ y DOWNLOAD para COM001.

## Hallazgos de F0 (H-01 … H-33)
El fichero `F0-hallazgos.md` se retira de esta rama (sigue en `f0-diseno`). Estado de cada hallazgo tras el recorte:

| ID | Tema | Estado | Cómo queda |
|---|---|---|---|
| H-01 | IDs de actividad comercial | Resuelto | D15, D18, D21, D24; `SALES_MANAGER` en su `allowed_roles` |
| H-02 | 12 pólizas en octubre frente a 150-300 en total | **RESUELTO** (propietario, 2026-11-05) | Las 150-300 pólizas son **altas nuevas entre septiembre y diciembre de 2026**, sin cartera previa. Octubre = mes ancla con 12 altas; las cancelaciones y renovaciones solo afectan a pólizas vendidas en ese periodo. Los objetivos de octubre (A4) no cambian |
| H-03 | Clearance del sujeto | Descartado por el recorte | No hay clearance |
| H-04 | Alcance de la regla de propietario | Resuelto | Propietario ⇒ READ/DOWNLOAD (`owner_id`) |
| H-05 | CREATE/UPDATE/DELETE/ADMINISTER | Resuelto | Solo MANAGER |
| H-06 | D11 para COM001 y su asistente | Resuelto | KB-Finanzas en su perfil y `SALES_MANAGER` en `allowed_roles` de D11 |
| H-07 | Actividad comercial en KB-EMPxxx | Resuelto | KB-Empleados única; acceso por `allowed_roles` |
| H-08 | READ sin DOWNLOAD frente al visor | Resuelto | READ y DOWNLOAD unidos (decidido por el propietario) |
| H-09 | «Vistas con alcance» | Resuelto | El alcance se aplica en Java (`data`); ninguna vista |
| H-10 | Quién propone las herramientas | Resuelto | Llamada de planificación validada por el backend (ADR 0003) |
| H-11 | Verificación frente a streaming | Resuelto | Streaming real y evento final `verification` como señal de calidad (el LLM solo ve texto autorizado) |
| H-12 | `NUMERIC(14,2)` y costes de IA | Descartado por el recorte | No hay precios; `NUMERIC(14,2)` en todo |
| H-13 | Contenido del chat y revocaciones | Resuelto por defecto (reversible) | El contenido solo vive en `messages` (propietario únicamente); se guarda sin caducidad y sin job de retención. Un texto ya generado no se «des-dice» si se revoca después un documento (las fuentes se re-autorizan al releer) |
| H-14 | Catálogo de herramientas | Resuelto | 4 herramientas; `get_expenses` **no se añade** (decidido); gastos y facturas solo por RAG sobre D07/D12/D13 |
| H-15 | Permisos de datos y conversaciones ajenas | Resuelto | Alcance por herramienta; conversaciones solo del propietario (ni MANAGER) |
| H-16 | Desglose del importe de gastos | Descartado | Importe único (`amount`); no hay desglose |
| H-17 | Factura→operación en un corredor | Resuelto | `invoices` emitidas (a la aseguradora) y recibidas (proveedores) |
| H-18 | Imperfecciones deliberadas | **Resuelto** (F2) | Declaradas en `generator/IMPERFECCIONES.md` y probadas; dentro del modelo de permisos |
| H-19 | Dos relojes | Resuelto de otro modo | Un `Clock` inyectable; el JWT usa tiempo real (ADR 0006) |
| H-20 | A1: el mes sale en pérdidas | **Pendiente** (informativo) | Con 9.680 € de primas los ingresos del asegurador no cubren los gastos (con las comisiones por producto del generador, 1.505,00 € en octubre frente a 7.250,00 € de gastos y 24.160,99 € de sueldos); el informe D01/D13 debe ser coherente con ello |
| H-21 | Varios roles por usuario | Descartado | Un rol por usuario (enum) |
| H-22 | API de Dify 1.18 no verificada | **Pendiente** (F4) | Se comprueba en el Swagger de la instancia; plan B en ADR 0003 |
| H-23 | Umbrales de contexto | Descartado | No hay reglas de contexto |
| H-24 | KB-Sales con un solo documento | Descartado | No existe KB-Sales (D06 en KB-Direccion) |
| H-25 | Grants inertes | Descartado | No hay grants |
| H-26 | Fuentes de los empleados | Resuelto | KB-General y KB-Empleados (filtro `owner_id`) |
| H-27 | Nómina propia por IA/SQL | Resuelto | Sin herramienta de nóminas; el propietario la lee del documento (RAG/descarga) |
| H-28 | §18 original truncado | Informativo | Los puntos 5-8 del modo de trabajo están inferidos; sin impacto |
| H-29 | Modulith o ArchUnit | Resuelto | Sin Modulith; ArchUnit opcional (ADR 0001) |
| H-30 | `/api/chat` frente a `/api/v1` | Resuelto | `POST /api/v1/chat` |
| H-31 | Cancelaciones y ventas | Resuelto | Ventas por `sale_date` sin importar el estado actual; la cancelación genera una comisión `CLAWBACK` en su mes |
| H-32 | Desnormalizaciones de documentos | Descartado | No existen `knowledge_bases` ni `document_versions` |
| H-33 | Tarifas de IA | Descartado | No hay precios |

## Decisiones del propietario (aplicadas)
1. **READ y DOWNLOAD juntos**: confirmado. COM001 descarga D11 y D15/D18/D21/D24.
2. **No se añade `get_expenses`**: confirmado. Gastos y facturas por chat solo vía RAG sobre D07, D12 y D13.
3. **Importes únicos**: sin desglose de impuestos; `expenses.amount` (7.250 € en octubre) e `invoices.amount`.
4. **Historial de chat**: se guarda sin caducidad y sin job de retención. *Decisión por defecto, reversible* (si se quiere retención, bastaría un job y un campo de caducidad).
5. **JWT con refresh token**: confirmado, con rotación y revocación de la familia ante reutilización ([ADR 0007](adr/0007-sesiones-jwt-refresh-rotativo.md)).

## Pendientes para el propietario
Ninguno: todos los pendientes están resueltos (H-02 incluido: 150-300 altas nuevas de septiembre a diciembre de 2026).

## Validaciones
Realizadas con scripts auxiliares no versionados (en el espacio de trabajo de la tarea, no en el repo); segunda pasada tras aplicar las decisiones del propietario:
- **DDL** ([schema.md](data-model/schema.md)) en un PostgreSQL 16 temporal (initdb propio, sin tocar clústeres existentes, eliminado al terminar): 32 sentencias, **12 tablas**, 20 `CREATE INDEX` explícitos (45 índices con PK y UNIQUE) y 54 CHECK creados sin errores. No existe ninguna columna de desglose de impuestos.
- **Números ancla de octubre** con datos de prueba: 12 pólizas, 9.680,00 € de primas, 968,00 € de comisiones de empleado (`kind = 'ACCRUAL'`), **7.250,00 € de gastos (suma de `expenses.amount`)**, 12.000,00 € de objetivos, cumplimientos 105,0 / 76,0 / 73,2 / 68,8 % y 80,7 % total; por empleado 3.150 / 2.280 / 2.050 / 2.200 €. Una póliza de septiembre cancelada en octubre (con su `CLAWBACK`) no altera esas cifras.
- **18 restricciones negativas** rechazadas por la BD (documento sin MANAGER, `RESTRICTED` con EMPLOYEE, KB-Empleados con EMPLOYEE o sin propietario, rol inexistente, `INDEXED` sin id de Dify, comisiones mal calculadas, `CANCELLED` sin fecha, gasto `PAYROLL`, periodo no mensual, rol de usuario inválido, gasto negativo, `sources` no array, y para `refresh_tokens`: `token_hash` duplicado, caducado al emitirse, `replaced_by` sin `revoked_at`, usuario inexistente).
- **Refresh en SQL**: rotación (la fila usada queda revocada con `replaced_by` y misma `family_id`) y revocación de toda la familia ante reutilización: 0 tokens activos tras el escenario. Es una prueba del modelo de datos, **no** de la lógica de la aplicación (que no existe aún).
- **Matriz**: 32 documentos sembrados desde [permissions-spec.yaml](data-model/permissions-spec.yaml); la consulta `rol = ANY(allowed_roles) OR owner_id = usuario` dio **0 diferencias** con el oráculo Python (DIR001 32, ACC001 14, COM001 13, cada EMPLOYEE 10; 99 celdas permitidas de 224). Con el oráculo sobre las 1.344 celdas usuario × documento × acción: READ y DOWNLOAD idénticos en todas; COM001 tiene READ y DOWNLOAD en D06, D11, D15, D18, D21, D24 y DENY en D01-D05, contratos y nóminas; ACC001 y EMP001 cumplen sus positivos. 42 casos negativos explícitos dan DENY (cifra histórica de F0; el cálculo literal de NEG-01/02/04/06 da 53 pares, ver nota en [F1](phases/F1-backend-base.md)), así como las 36 combinaciones EMP↔EMP, ACC→Dirección y contratos/nóminas, COM→D01-D05 y contratos/nóminas, EMP→Dirección/Finanzas. Ningún caso negativo contradice READ+DOWNLOAD.
- **Mermaid**: `mermaid_check.py` sobre `docs/`: **11 de 11 bloques OK**.
- **Enlaces internos** (incluidas anclas): 59 comprobados, 0 rotos. Búsqueda de términos obsoletos (11 tablas, «JWT sin refresh», impuestos, `get_expenses` como pendiente, 54 tablas, Modulith, F6-F9): solo aparecen como histórico explícito o como decisión tomada.

**No verificado:** la API real de Dify 1.18 (filtros de metadatos, `retrieve`); las conversaciones ajenas, el alcance `OWN/TEAM` de las herramientas y toda la lógica de sesión (rotación, cookie, logout) en código: se definen en el diseño y se probarán en F1-F4; rendimiento con volumen; recall y citas (no existe aún el conjunto de evaluación); la sintaxis Mermaid se comprobó con el parser de Mermaid 11 en navegador headless, no con el visor de GitHub.
