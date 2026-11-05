# ADR 0002 — ABAC mínima con un único `AccessPolicyService`

Estado: aceptado (revisado en la rama `simple`). **Reemplaza** la versión de F0 (grants en `document_permissions`, clearance por rol, reglas de contexto).

## Contexto
La propiedad central de SegurPlus: **un usuario nunca obtiene información que no puede leer, por ningún canal** (documentos, chat, SQL, conversaciones, descargas, API directa). Hay puntos de decisión en REST, herramientas de IA, recuperación RAG y descargas, y dos fuentes potenciales de verdad (BD y filtros de Dify). Con 4 roles y 32 documentos no hace falta una tabla de grants.

## Decisión
**Un único servicio** `auth.AccessPolicyService` toma toda decisión de autorización. Nadie más decide: ni el frontend, ni el prompt, ni Dify, ni los controladores por su cuenta.

### Entradas
| Elemento | Contenido | Origen |
|---|---|---|
| `Subject` | `userId`, `role`, `employeeCode` | Solo el JWT. Nunca el cuerpo ni los parámetros |
| Documento | `allowed_roles`, `owner_id`, `deleted_at`, `classification`, `kb` | Tabla `documents` |
| Acción | READ, DOWNLOAD, CREATE, UPDATE, DELETE, ADMINISTER | La operación |

### Evaluación
1. **Deny by default**.
2. Documento borrado lógicamente ⇒ DENY.
3. `READ`/`DOWNLOAD`: ALLOW si `subject.role ∈ allowed_roles` **o** `owner_id = subject.userId`.
4. `CREATE`/`UPDATE`/`DELETE`/`ADMINISTER`: ALLOW solo si el rol es `MANAGER`.
5. Resultado `Decision{ALLOW|DENY, reasonCode}`. El `reasonCode` va a `audit_logs`; **al cliente solo llega 404** (403 en acciones de escritura sobre algo que el usuario ya puede leer).

Cada DENY y cada DOWNLOAD se auditan (metadatos, sin contenido).

### Datos de la matriz
- `MANAGER` siempre está en `allowed_roles`. Las excepciones de COM001 (D06, D11, D15, D18, D21, D24) se resuelven añadiendo `SALES_MANAGER` a `allowed_roles` de esos documentos.
- La clasificación es un enum **informativo**; solo interviene en dos CHECK de BD (`RESTRICTED` nunca con `EMPLOYEE`; KB-Empleados siempre con propietario y sin `EMPLOYEE`) que protegen de errores de configuración.

### Traducción a consultas
- **JPA `Specification`** para listados, detalle, conteos y facetas: `deletedAt IS NULL AND (:role = ANY(allowedRoles) OR ownerId = :userId)`. Una sola, para que un conteo nunca revele documentos ocultos.
- **Datos de negocio**: `scopeFor(subject, herramienta)` devuelve `OWN` (`employee_id = :me`), `TEAM` (los usuarios con rol EMPLOYEE), `GLOBAL` o `AGGREGATE` ([permissions-mapping §5](../data-model/permissions-mapping.md)). El `employee_id` sale del `Subject`.
- **Metadatos para Dify**: se envían `allowed_roles` y `owner_id` como metadatos del documento; el filtro de recuperación (`allowed_roles` contiene mi rol, o `owner_id` = yo) es una **cota**. Los operadores exactos de la API 1.18 no están verificados (se comprueban en F3).

### La re-autorización es el control
Cada fragmento devuelto por Dify se mapea a `documents.id` y pasa por `authorizeBatch(subject, READ, ids)` (una consulta con la `Specification`). Sin mapeo, borrado o no autorizado ⇒ descartado. Ver ADR 0003.

### Verificación
La matriz esperada se genera de [permissions-spec.yaml](../data-model/permissions-spec.yaml) con un oráculo independiente y se compara en todos los canales ([permissions-mapping §6](../data-model/permissions-mapping.md)).

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Tabla de grants + clearance por rol (versión F0) | Más tablas, más lógica y más casos de prueba que los que 4 roles y 32 documentos justifican |
| RBAC puro por rol | No expresa «propietario» (los empleados ven sus propios documentos) |
| Autorización en Dify o en el prompt | Es detección, no prevención |
| Motor externo (OPA, Cedar) | Un servicio y un DSL más; no se traduce a `Specification` |
| Row-Level Security de PostgreSQL | Defensa en profundidad opcional; no es el control principal |

## Consecuencias
- (+) Una regla de una línea, un sitio, un oráculo de tests simple.
- (+) Quitar un rol de `allowed_roles` o cambiar el propietario surte efecto al instante, sin reindexar (el control está en la BD).
- (−) Sin reglas de contexto (descargas masivas, horario): mejora opcional, no necesaria para el portfolio.
- (−) READ y DOWNLOAD van unidos: quien lee puede descargar (ver DECISIONES).
- (−) No hay caducidad ni revocación por usuario individual: solo por rol y propietario.
