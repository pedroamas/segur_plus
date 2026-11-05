# Mapeo de la matriz de permisos a datos

Objetivo: que cada celda de [`../security/permissions-matrix.md`](../security/permissions-matrix.md) tenga una traducción **mecánica** a filas de `document_permissions`, a la regla de propietario o a `role_permissions`, de modo que (a) la siembra se genere sin escribir grants a mano y (b) la matriz esperada (usuario × documento × acción) se calcule en los tests y se compare con lo que responde el sistema por **todos** los canales (REST, descarga, chat/RAG, herramientas SQL, conversaciones).

Fuente declarativa: [`permissions-spec.yaml`](permissions-spec.yaml). Este fichero explica las reglas; el YAML es el dato.

## 1. Semántica de la política (resumen de la matriz, reglas 1-3)
Para un usuario `u`, documento `d` (no borrado) y acción `a`:

```
ALLOW(u, d, a)  =  [ a ∈ {READ, DOWNLOAD}  ∧  d.owner_employee_id = u.employee_id ]          -- regla de propietario
                ∨  [ rank(d.classification) ≤ rank(clearance(u))
                     ∧ ( existe grant activo (document_id=d, subject ∈ S(u), action=a, no revocado, no caducado)
                         ∨ ( a ∈ {CREATE, UPDATE, DELETE, ADMINISTER} ∧ role_permissions de algún rol de u incluye DOCUMENT:a:GLOBAL ) ) ]
S(u) = { (USER, u.id) } ∪ { (ROLE, r.id) : r ∈ roles(u) } ∪ { (DEPARTMENT, u.department_id) }
clearance(u) = máximo roles.clearance_level de los roles de u
```
Aclaraciones que la matriz no daba y se fijan aquí (ver hallazgos):
- **Propietario ⇒ solo READ y DOWNLOAD** y salta la clasificación (un empleado lee su contrato `RESTRICTED`) — H-04. No se materializa en `document_permissions`: se deriva de `owner_employee_id`.
- **Clearance del sujeto**: `roles.clearance_level` — MANAGER=RESTRICTED, ACCOUNTANT=CONFIDENTIAL, SALES_MANAGER=CONFIDENTIAL, EMPLOYEE=INTERNAL — H-03.
- **CREATE/UPDATE/DELETE/ADMINISTER**: no son grants por documento (CREATE ni siquiera tiene documento aún); salen de `role_permissions` con `resource_type=DOCUMENT`, `scope=GLOBAL`, y por defecto solo MANAGER — H-05.
- Los grants son **solo de concesión**; no hay filas DENY (deny by default).
- La caducidad se evalúa con el reloj de negocio en la consulta, no se «limpia» por job (un job solo marca `revoked_at` por higiene).

## 2. Cómo se traduce cada celda
| Celda de la matriz | Traducción |
|---|---|
| `R D` en rol X | 2 filas `document_permissions`: `(doc, ROLE, X, READ)` y `(doc, ROLE, X, DOWNLOAD)` |
| `R` en rol X | 1 fila `(doc, ROLE, X, READ)` |
| `—` | ninguna fila (deny por defecto) |
| `owner: R D` | ninguna fila. `documents.owner_employee_id = employees.id` del propietario; la regla de propietario da READ y DOWNLOAD |
| `solo actividad: R` para SALES_MANAGER | 1 fila `READ` ROLE sobre el documento de categoría `COMMERCIAL_ACTIVITY` de cada empleado (D15, D18, D21, D24; ver H-01) y nada sobre contrato/nómina |
| Clasificación | `documents.classification` (FK a `document_classifications`); se compara con `roles.clearance_level` |
| KB | `documents.knowledge_base_id` (+ `dify_dataset_id`); además filtra qué fuentes puede usar el asistente |
| Un grant cuyo sujeto tiene clearance inferior | **inerte** (no concede nada salvo propietario). La validación de alta de grants debe avisar (H-25). En la siembra no hay ninguno (comprobado) |

## 3. Filas resultantes de la siembra
| Bloque | Documentos | Grants ROLE | Detalle |
|---|---|---:|---|
| Dirección | D01-D05 | 10 | MANAGER READ+DOWNLOAD |
| Comercial | D06 | 4 | MANAGER y SALES_MANAGER, READ+DOWNLOAD |
| Finanzas (sin D11) | D07-D10, D12, D13 | 24 | MANAGER y ACCOUNTANT, READ+DOWNLOAD |
| Comisiones | D11 | 5 | MANAGER y ACCOUNTANT READ+DOWNLOAD; SALES_MANAGER solo READ |
| Documentos de empleados | D14-D25 | 28 | MANAGER READ+DOWNLOAD en los 12 (24 filas) + SALES_MANAGER READ en los 4 de actividad |
| Manuales y generales | D26-D29, G01-G03 | 56 | 4 roles × READ+DOWNLOAD × 7 documentos |
| **Total** | **32** | **127** | |

Celdas de la matriz esperada: 7 usuarios × 32 documentos × 6 acciones = **1.344**; con la política anterior resultan **321 ALLOW** y 1.023 DENY:

| Usuario | READ | DOWNLOAD | CREATE / UPDATE / DELETE / ADMINISTER | ALLOW |
|---|---:|---:|---:|---:|
| DIR001 (MANAGER) | 32 | 32 | 32 cada una | 192 |
| ACC001 (ACCOUNTANT) | 14 | 14 | 0 | 28 |
| COM001 (SALES_MANAGER) | 13 | 8 | 0 | 21 |
| EMP001..EMP004 (EMPLOYEE), cada uno | 10 | 10 | 0 | 20 (×4 = 80) |

(Cifras calculadas expandiendo `permissions-spec.yaml` con un script auxiliar de F0; deben reproducirse en el test generador de F1.) Desglose de lectura: ACC001 = D07-D13 (7) + 7 manuales/generales = 14; COM001 = D06 + D11 + 4 actividades + 7 manuales/generales = 13 (DOWNLOAD: 8, sin D11 ni actividades); cada EMP = 3 propios + 7 manuales/generales = 10.

## 4. Asistentes y fuentes → filas de `ai_*`
| Asistente (`ai_assistants.code`) | `role_id` | `ai_assistant_knowledge_bases` | Herramientas (`ai_assistant_tools`) | Alcance de datos |
|---|---|---|---|---|
| `management` | MANAGER | filas fijas: MANAGEMENT, SALES, FINANCE, INSURANCE, COMPANY; selector `ALL_EMPLOYEE_KBS` | todas | GLOBAL |
| `accounting` | ACCOUNTANT | COMPANY, FINANCE, INSURANCE | herramientas financieras y de comisiones | FINANZAS GLOBALES sin salarios individuales (`payroll` solo `AGGREGATE`) |
| `sales` | SALES_MANAGER | COMPANY, INSURANCE, SALES; selector `ALL_EMPLOYEE_KBS` **con** `category_id = COMMERCIAL_ACTIVITY` | ventas, objetivos, comisiones, pólizas | TEAM (empleados con `manager_employee_id = COM001`) |
| `employee` | EMPLOYEE | COMPANY, INSURANCE; selector `OWN_EMPLOYEE_KB` | ventas, comisiones, objetivos, pólizas propias | OWN (por JWT) |

Reglas:
1. Las filas de `ai_assistant_knowledge_bases` son una **cota superior**. Lo que el usuario recibe es `candidatos ∩ ALLOW(u, d, READ)`. Un asistente nunca amplía permisos.
2. `OWN_EMPLOYEE_KB` se resuelve a `knowledge_bases.owner_employee_id = employee(u)`; `ALL_EMPLOYEE_KBS` a las KB con `owner_employee_id IS NOT NULL`.
3. El asistente `sales` apunta a datasets KB-EMPxxx **solo con la categoría** de actividad comercial; aun así cada fragmento se re-autoriza (los contratos y nóminas de esas KB darían DENY a COM001) — H-07.
4. D11 (SALES_MANAGER READ) **no** está en las fuentes del asistente `sales` (su KB es FINANCE): COM001 lo lee por REST pero el chat no lo recupera. Pendiente de decidir (H-06).

## 5. Permisos de datos (`role_permissions`) — propuesta (H-15)
La matriz solo cubre documentos y asistentes. Para REST y herramientas SQL se propone esta tabla (`recurso: acción/alcance`). Todo lo no listado está denegado. **Requiere confirmación del propietario.**

| Recurso | MANAGER | ACCOUNTANT | SALES_MANAGER | EMPLOYEE |
|---|---|---|---|---|
| DOCUMENT | CREATE/UPDATE/DELETE/ADMINISTER GLOBAL | — | — | — |
| CONVERSATION | READ/CREATE/DELETE OWN | ídem | ídem | ídem |
| CUSTOMER | READ GLOBAL | — | READ TEAM | READ OWN (clientes asignados) |
| LEAD, OPPORTUNITY, SALES_ACTIVITY | READ GLOBAL | — | READ TEAM | READ/CREATE/UPDATE OWN |
| SALE | READ GLOBAL | READ AGGREGATE | READ TEAM | READ OWN |
| POLICY | READ GLOBAL | READ AGGREGATE | READ TEAM | READ OWN (pólizas de sus ventas) |
| COMMISSION | READ GLOBAL | READ GLOBAL (D11) | READ TEAM (D11) | READ OWN |
| TARGET | READ/UPDATE GLOBAL | — | READ TEAM | READ OWN |
| EXPENSE, INVOICE, PAYMENT, LEDGER | READ GLOBAL | READ/CREATE/UPDATE GLOBAL | — | — |
| PAYROLL | READ GLOBAL | READ **AGGREGATE** | — | READ OWN (solo REST/documento, sin herramienta IA; H-27) |
| CONTRACT | READ GLOBAL | — | — | READ OWN |
| PERFORMANCE_REVIEW | READ/CREATE/UPDATE GLOBAL | — | READ/CREATE/UPDATE TEAM | READ OWN |
| LEAVE | READ/UPDATE GLOBAL | CREATE/READ OWN | READ/UPDATE TEAM + CREATE/READ OWN | CREATE/READ OWN |
| USER, ASSISTANT, KNOWLEDGE_BASE | ADMINISTER GLOBAL | — | — | — |
| AUDIT_LOG, EVALUATION | READ GLOBAL | — | — | — |

Se traducen a filas de `permissions` (`resource_type, action, scope` únicas) y `role_permissions`. `OWN`/`TEAM`/`GLOBAL`/`AGGREGATE` se convierten en `Specification` (ADR 0002) o en el parámetro de alcance de los servicios de `analytics` (ADR 0005).

## 6. Generación y prueba automática de la matriz
Procedimiento (descrito, sin código; se implementa en F1-F3):

1. **Cargar** `permissions-spec.yaml`. Un test valida: 32 documentos; 9 KBs distintas; códigos únicos; todo grant referencia un rol y una acción válidos; ningún grant inerte.
2. **Sembrar**: por cada documento, insertar `document_permissions` (ROLE, rol, acción) según `grants` (127 filas) y `owner_employee_id`; insertar `roles.clearance_level`; insertar los `role_permissions` de la sección 5.
3. **Calcular** la matriz esperada con la fórmula de la sección 1 *en el test* (implementación independiente de `AccessPolicyService`, a propósito: un oráculo simple y legible).
4. **Ejecutar** por cada celda y por cada canal:
   | Canal | Comprobación |
   |---|---|
   | REST `GET /api/v1/documents/{id}` y listado | ALLOW READ ⇒ 200 y aparece en el listado; DENY ⇒ **404** y no aparece (ni en conteos ni facetas) |
   | REST `GET /api/v1/documents/{id}/download` | ALLOW DOWNLOAD ⇒ 200 y fila `DOCUMENT_DOWNLOAD` en `audit_logs`; DENY ⇒ 404 |
   | Recuperación RAG (WireMock de Dify devolviendo fragmentos de **todos** los documentos) | solo sobreviven los fragmentos con ALLOW READ; el resto genera `RAG_FRAGMENT_DROPPED` |
   | Citas del chat | cada cita apunta a un documento con ALLOW READ |
   | Herramientas SQL | para cada usuario, los datos coinciden con su alcance (OWN/TEAM/GLOBAL/AGGREGATE) y ningún dato de otro empleado |
   | Conversaciones | solo las propias; ajena ⇒ 404 |
5. **Mutaciones** (CA-2, «usuario retirado de un grant»): revocar un grant ⇒ la celda pasa a DENY en todos los canales **sin reindexar**; caducar un grant (avanzando el reloj de negocio) ⇒ DENY; cambiar `classification` de un documento a un nivel superior al clearance ⇒ DENY aunque el grant exista; cambiar el propietario ⇒ la celda se mueve de empleado.
6. **Sensibilidad**: un test comprueba que eliminar cualquiera de las 127 filas cambia al menos una celda esperada (no hay grants redundantes) y que añadir un grant fuera de la especificación hace fallar el test.

## 7. Casos negativos obligatorios de la matriz → identificadores de prueba
| ID | Caso (matriz) | Celdas / canales | Resultado |
|---|---|---|---|
| NEG-01 | EMP001 → D17/D18/D19 | READ, DOWNLOAD, chat, SQL | DENY / 404 / sin datos |
| NEG-02 | EMP001 → D11 | READ, DOWNLOAD, chat | DENY |
| NEG-03 | EMP001 → conversación de EMP002 | REST | 404 |
| NEG-04 | ACC001 → D02, D04, D05, contrato y nómina de cualquier empleado | todos | DENY |
| NEG-05 | ACC001 → salarios individuales por chat o SQL | tools | sin datos (solo `payroll_runs` agregado) |
| NEG-06 | COM001 → D01-D05, contratos y nóminas | todos | DENY |
| NEG-07 | Cualquiera → documento con ID modificado en la URL | REST | 404 |
| NEG-08 | Petición con `employee_id`/`role`/`assistantId` ajeno | REST, chat | ignorado; resultado del JWT |
| NEG-09 | Usuario retirado de un grant | listados, citas, retrieval | desaparece sin reindexar |
| NEG-10 | COM001 → D15 DOWNLOAD (solo READ concedido) | REST | DENY (la lectura sí) |
| NEG-11 | DIR001 → nada denegado por clasificación | REST | ALLOW (control positivo del techo RESTRICTED) |
| NEG-12 | Cruces CA-3: empleado↔empleado, ACC↔DIR, COM↔DIR | datos de cualquier canal | ninguno |
