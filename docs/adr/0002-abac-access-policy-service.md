# ADR 0002 — ABAC con un único `AccessPolicyService`

Estado: propuesto (F0)

## Contexto
La propiedad central de SegurPlus: **un usuario nunca obtiene información que no puede leer, por ningún canal** (documentos, chat, SQL, conversaciones, descargas, API directa). Hay cuatro puntos de decisión (REST, herramientas de IA, recuperación RAG, descargas) y dos «fuentes» de verdad potenciales (BD y filtros de Dify). La matriz ([permissions-matrix.md](../security/permissions-matrix.md)) define reglas ABAC: rol + departamento + propietario + clasificación + acción + contexto.

## Decisión
**Un único servicio** `security.AccessPolicyService` toma toda decisión de autorización. Nadie más decide: ni el frontend, ni el prompt, ni Dify, ni los controladores por su cuenta.

### Entradas
| Elemento | Contenido | Origen |
|---|---|---|
| `Subject` | `userId`, `employeeId`, `departmentId`, roles, `clearance` (máx. de `roles.clearance_level`) | Solo el JWT + BD/caché corta de roles. Nunca el cuerpo ni los parámetros de la petición |
| `Resource` | tipo, `id`, `classification`, `knowledgeBaseId`, `ownerEmployeeId`, departamento, dueño del dato | Cargado por el módulo propietario |
| `Action` | READ, CREATE, UPDATE, DELETE, DOWNLOAD, ADMINISTER | La operación solicitada |
| `Context` | instante de negocio (`BusinessClock`), canal (`REST`, `AI_TOOL`, `RAG`), contador de descargas recientes (de `audit_logs`), correlación | El servidor. Reglas de contexto: matriz, regla 4 |

### Evaluación (`decide`)
1. **Deny by default**. Si no se demuestra ALLOW, es DENY.
2. Recurso borrado lógicamente ⇒ DENY.
3. Regla de propietario: `action ∈ {READ, DOWNLOAD}` y `resource.ownerEmployeeId = subject.employeeId` ⇒ ALLOW (salta clasificación).
4. Clasificación: `rank(resource.classification) > rank(subject.clearance)` ⇒ DENY.
5. Grant: existe fila activa en `document_permissions` (no revocada, no caducada a la hora de negocio) para `(document, S(subject), action)` con `S(subject) = {USER:id, ROLE:r…, DEPARTMENT:d}` ⇒ ALLOW. Para recursos de datos (no documentales) se usa `role_permissions` con su alcance.
6. UPDATE/DELETE/ADMINISTER/CREATE: `role_permissions` con `DOCUMENT:<acción>:GLOBAL` (por defecto solo MANAGER).
7. **Contexto**: descargas masivas, intentos fallidos o franja horaria pueden elevar a DENY o marcar auditoría reforzada. Los umbrales son configuración (no definidos en la matriz; H-23).
8. Resultado: `Decision{ALLOW|DENY, reasonCode}`. El `reasonCode` va a `audit_logs`; **al cliente solo llega 404** (o 403 en acciones sobre un recurso cuya existencia el usuario ya conoce, p. ej. UPDATE sobre algo que puede leer).

Cada DENY y cada DOWNLOAD se auditan (metadatos, sin contenido).

### Traducción a consultas (listados filtrados en la consulta, no en memoria)
El módulo dueño del recurso implementa el SPI `ResourceAccessProvider` (así `security` no depende de `documents`):

- **JPA `Specification`**: `toSpecification(subject, tipo, acción)` devuelve un predicado componible. Para documentos:
  `deleted_at IS NULL AND ( (acción ∈ {READ,DOWNLOAD} AND owner_employee_id = :employeeId) OR ( rank(classification) ≤ :clearanceRank AND (EXISTS grant activo para (:userId, :roleIds, :deptId, acción) OR rol con permiso global) ) )`.
  El `EXISTS` usa el índice `ix_document_permissions_subject`; `rank(...)` se resuelve con un `IN (clasificaciones con rank ≤ clearance)` calculado antes (no hay join). Listados, conteos y facetas usan **la misma** `Specification`, de modo que un conteo nunca revela documentos ocultos.
- **Datos de negocio** (ventas, comisiones, etc.): el alcance `OWN`/`TEAM`/`DEPARTMENT`/`GLOBAL`/`AGGREGATE` se traduce a un predicado (`employee_id = :me`, `employee_id IN (:team)`, sin filtro, o a la vista agregada). El `employee_id` sale del `Subject`, jamás de un parámetro.
- **Filtros de metadatos de Dify**: `toMetadataFilter(subject, assistantContext)` produce una **cota superior** (condiciones sobre `kb`, `classification` ≤ clearance, `category` si el asistente lo restringe, o `owner_employee_id = :me` como alternativa). Los metadatos que se envían a Dify son atributos del documento (`doc_id`, `kb`, `classification`, `category`, `owner_employee_id`, `version`); **nunca los grants**.
  Los operadores exactos de filtro de la API de Dify 1.18 hay que comprobarlos en su Swagger (no verificado en F0; H-22); donde el operador no exista se filtra solo por dataset y la re-autorización cubre el resto.

### La re-autorización es el control
El filtro de Dify es una optimización. Cada fragmento devuelto se mapea a `documents.id` (por `doc_id`/`dify_document_id`) y se pasa por `AccessPolicyService.authorizeBatch(subject, READ, ids)` — **una** consulta con la `Specification`. Un fragmento sin mapeo, de un documento borrado o no autorizado se descarta (fallo cerrado). Ver ADR 0003.

### Verificación
La matriz esperada se genera de [permissions-spec.yaml](../data-model/permissions-spec.yaml) con un oráculo independiente y se compara por todos los canales ([permissions-mapping §6](../data-model/permissions-mapping.md)). ArchUnit prohíbe consultas a repositorios de recursos protegidos que no pasen por un servicio con `Subject`.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| RBAC puro por rol | No expresa propietario ni clasificación ni expiración |
| Autorización en Dify (metadatos, `user`) o en el prompt | Es detección, no prevención; contradice los principios 1, 5 y 6 del brief |
| Autorización dispersa en cada controlador | Divergencias entre REST, IA y RAG (justo lo que el diseño evita) |
| Motor externo (OPA, Cedar, Casbin) | Añade un servicio/DSL; no se traduce a `Specification` de JPA y complica los listados filtrados en consulta. Podría revisarse si las reglas crecen |
| Row-Level Security de PostgreSQL como control principal | Exige variable de sesión por petición y complica el pool; se reserva como **defensa en profundidad opcional** (F8) para tablas sensibles |

## Consecuencias
- (+) Una regla, un sitio: cambios de política se prueban una vez; el oráculo de tests detecta regresiones en todos los canales.
- (+) «Usuario retirado de un grant» deja de ver el documento al instante sin reindexar (los grants no viven en Dify).
- (−) Cada recurso nuevo debe implementar el SPI y sus tests de autorización (definición de hecho).
- (−) La caché de roles debe ser corta; un cambio de rol surte efecto en minutos como mucho (el JWT lleva `token_version` para invalidar).
- Abierto: clearance del sujeto, alcance de CREATE/UPDATE, umbrales de contexto → H-03, H-05, H-23.
