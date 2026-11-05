# Mapeo de la matriz de permisos a datos y tests

Cómo se traduce la [matriz](../security/permissions-matrix.md) a columnas, consultas y tests. Fuente declarativa: [permissions-spec.yaml](permissions-spec.yaml). Esquema: [schema.md](schema.md).

## 1. Semántica
```
READ(u, d), DOWNLOAD(u, d)  =  d.deleted_at IS NULL  AND  ( u.role = ANY(d.allowed_roles)  OR  d.owner_id = u.id )
CREATE / UPDATE / DELETE / ADMINISTER  =  u.role = 'MANAGER'
```
Sin grants, sin clearance y sin reglas de contexto. La clasificación es informativa; solo participa en dos CHECK de la BD que impiden configuraciones peligrosas (`RESTRICTED` sin `EMPLOYEE`; KB-Empleados con propietario y sin `EMPLOYEE`).

## 2. Cómo se traduce cada celda
| Celda de la matriz | Dato |
|---|---|
| Rol con acceso | el rol figura en `documents.allowed_roles` |
| «owner» (empleado propietario) | `documents.owner_id = users.id` |
| Excepción de COM001 (D06, D11, D15, D18, D21, D24) | `SALES_MANAGER` en `allowed_roles` de esos documentos |
| MANAGER en todo | `MANAGER` siempre en `allowed_roles` (CHECK `ck_documents_roles`) |
| Sin acceso | el rol no está y el usuario no es propietario |

## 3. Tres traducciones de la misma regla
| Donde se aplica | Traducción |
|---|---|
| Listados y detalle (REST) | `Specification<Document>`: `deletedAt IS NULL AND (:role = ANY(allowedRoles) OR ownerId = :userId)`; conteos y facetas usan la misma. El rol y el `userId` salen del JWT |
| Metadatos enviados a Dify | `doc_id`, `kb`, `classification`, `allowed_roles` (como lista separada por comas), `owner_id`. Filtro de recuperación según el rol: ver tabla del asistente en la matriz. Es una **cota** (la API de Dify 1.18 no está verificada, ver DECISIONES) |
| Re-autorización de fragmentos | `authorizeBatch(user, READ, documentIds)`: una consulta con la misma `Specification`; fragmento sin mapeo a `documents.id` o no autorizado ⇒ descartado (fallo cerrado) |

## 4. Casos negativos obligatorios → identificadores de prueba
| ID | Caso | Canales | Resultado |
|---|---|---|---|
| NEG-01 | EMP001 → D17-D25 (otro empleado) | READ, DOWNLOAD, listado, RAG, chat, SQL | DENY / 404 / sin datos |
| NEG-02 | EMP001 → D11 y D01-D13 | READ, DOWNLOAD, chat | DENY |
| NEG-03 | EMP001 → conversación de EMP002 | REST | 404 |
| NEG-04 | ACC001 → D01-D06 y D14-D25 (contratos y nóminas) | todos | DENY |
| NEG-05 | ACC001 → salarios individuales | chat, SQL | sin datos (ninguna herramienta los expone) |
| NEG-06 | COM001 → D01-D05 y contratos/nóminas (D14, D16, D17, D19, D20, D22, D23, D25) | todos | DENY |
| NEG-07 | Cualquiera → documento con ID alterado | REST | 404 |
| NEG-08 | Petición con `employee_id`/`role`/`assistantId` ajeno | REST, chat | ignorado; resultado del JWT |
| NEG-09 | Rol retirado de `allowed_roles` o cambio de propietario | listados, citas, RAG | desaparece sin reindexar |
| NEG-10 | Metadatos de Dify obsoletos (índice con `allowed_roles` antiguo) | RAG | fragmento descartado en la re-autorización |
| NEG-11 | Cruces: EMP↔EMP, ACC↔DIR, COM↔DIR | cualquier canal | ningún dato |
| NEG-12 | MANAGER → conversación de otro usuario | REST | 404 |
| POS-01 | Controles positivos (READ y DOWNLOAD): DIR001 todo; COM001 D06, D11, D15, D18, D21, D24; EMP001 D14-D16; ACC001 D07-D13 | REST | ALLOW |

Decidido: READ y DOWNLOAD van juntos. La matriz de F0 tenía un caso «COM001 no descarga D15»; ya no existe y ningún caso negativo lo contradice. POS-01 cubre READ **y** DOWNLOAD de COM001 sobre D06, D11, D15, D18, D21, D24.

## 5. Alcance de las herramientas SQL
`OWN` = `employee_id = JWT`; `TEAM` = los usuarios con rol EMPLOYEE (los 4 comerciales); `GLOBAL` = sin filtro; `AGGREGATE` = solo totales, sin desglose por empleado.

| Herramienta | MANAGER | ACCOUNTANT | SALES_MANAGER | EMPLOYEE |
|---|---|---|---|---|
| `get_sales_summary` | GLOBAL | AGGREGATE | TEAM | OWN |
| `get_commissions` | GLOBAL | GLOBAL | TEAM | OWN |
| `get_policies_by_status` | GLOBAL | AGGREGATE | TEAM | OWN |
| `get_targets` | GLOBAL | — | TEAM | OWN |

Ninguna herramienta expone `users.monthly_salary` ni acepta `employee_id` como parámetro.

## 6. Generación y prueba automática de la matriz
1. **Cargar** `permissions-spec.yaml`. Un test valida: 32 documentos; 4 KBs; códigos únicos; `MANAGER` en todos los `allowed_roles`; roles válidos.
2. **Sembrar** `documents.allowed_roles` y `owner_id` desde el YAML.
3. **Oráculo**: la fórmula de la sección 1 implementada *en el test* (independiente de `AccessPolicyService`).
4. **Ejecutar** por usuario × documento × acción y por canal:
   | Canal | Comprobación |
   |---|---|
   | REST detalle y listado | ALLOW ⇒ 200 y aparece; DENY ⇒ **404** y no aparece (ni en conteos) |
   | REST descarga | ALLOW ⇒ 200 y fila `DOCUMENT_DOWNLOAD` en `audit_logs`; DENY ⇒ 404 |
   | Recuperación RAG (WireMock de Dify devolviendo fragmentos de **todos** los documentos) | solo sobreviven los permitidos; el resto genera `FRAGMENT_DROPPED` |
   | Citas del chat | cada cita apunta a un documento con ALLOW |
   | Herramientas SQL | los datos coinciden con el alcance de la sección 5 |
   | Conversaciones | solo las propias; ajena ⇒ 404 |
5. **Mutaciones**: quitar un rol de `allowed_roles` ⇒ DENY en todos los canales sin reindexar; cambiar `owner_id` ⇒ la celda se mueve de empleado.
6. **Invariantes de BD**: los intentos de violar los CHECK (documento sin MANAGER, `RESTRICTED` con EMPLOYEE, KB-Empleados con EMPLOYEE) son rechazados.

Esta lógica se validó ya en F0-simple con PostgreSQL 16 (la consulta de la sección 1 contra el oráculo del YAML: 0 diferencias; 99 celdas permitidas de 224, idénticas para READ y DOWNLOAD; casos negativos denegados).
