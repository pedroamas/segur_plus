# Matriz de permisos

Política de acceso de SegurPlus. Fuente declarativa para tests: [permissions-spec.yaml](../data-model/permissions-spec.yaml). Decisión: [ADR 0002](../adr/0002-abac-access-policy-service.md).

Convenciones: `R` = READ, `D` = DOWNLOAD, `—` = sin acceso. Cualquier acceso no listado está **denegado**. Clasificación: `PUBLIC < INTERNAL < CONFIDENTIAL < RESTRICTED` (informativa, con dos invariantes de seguridad; ver reglas).

## Reglas (ABAC mínima)
1. **Deny by default.** Documento borrado lógicamente ⇒ DENY.
2. **READ y DOWNLOAD**: permitidos si el **rol** del usuario está en `documents.allowed_roles` **o** el usuario es el `owner` (`documents.owner_id`). No hay tabla de grants.
3. **CREATE, UPDATE, DELETE, ADMINISTER**: solo `MANAGER`.
4. `MANAGER` figura siempre en `allowed_roles` (CHECK en BD). `RESTRICTED` nunca incluye `EMPLOYEE`, y los documentos de la KB-Empleados nunca incluyen `EMPLOYEE` y siempre tienen `owner` (CHECK en BD): un empleado solo llega a ellos como propietario.
5. Las excepciones de COM001 (D06 y D11, y la actividad comercial D15, D18, D21, D24) **no son reglas aparte**: se resuelven poniendo `SALES_MANAGER` en `allowed_roles` de esos documentos.
6. La misma regla se evalúa igual en REST, herramientas de IA y recuperación RAG. Los mismos `allowed_roles`/`owner_id` viajan como **metadatos** a Dify para filtrar la recuperación; cada fragmento se **re-autoriza** en el backend antes de llegar al LLM (el filtro es optimización, la re-autorización es el control).
7. Recurso no autorizado ⇒ **404** (no revela existencia). Listados, conteos y facetas se filtran en la consulta.

## Documentos
| ID | Documento | KB | Clasif. | `allowed_roles` | `owner` |
|---|---|---|---|---|---|
| D01 | Informe mensual de dirección | DIRECCION | CONFIDENTIAL | MANAGER | — |
| D02 | Presupuesto anual 2026 (incluye salarios) | DIRECCION | RESTRICTED | MANAGER | — |
| D03 | Cuadro de mando KPI | DIRECCION | CONFIDENTIAL | MANAGER | — |
| D04 | Plan estratégico 2026-2028 | DIRECCION | RESTRICTED | MANAGER | — |
| D05 | Actas comité de dirección | DIRECCION | RESTRICTED | MANAGER | — |
| D06 | Rendimiento comercial (CRM) | DIRECCION | CONFIDENTIAL | MANAGER, **SALES_MANAGER** | — |
| D07 | Facturas emitidas y recibidas | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT | — |
| D08 | Libro mayor (nóminas agregadas) | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT | — |
| D09 | Extracto bancario (nóminas agregadas) | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT | — |
| D10 | Conciliación bancaria | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT | — |
| D11 | Liquidación de comisiones (todos los empleados) | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT, **SALES_MANAGER** | — |
| D12 | Registro de gastos | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT | — |
| D13 | Informe de cierre contable | FINANZAS | CONFIDENTIAL | MANAGER, ACCOUNTANT | — |
| D14, D17, D20, D23 | Contrato de EMP001 / 002 / 003 / 004 | EMPLEADOS | RESTRICTED | MANAGER | el empleado |
| D15, D18, D21, D24 | Actividad comercial de EMP001 / 002 / 003 / 004 | EMPLEADOS | CONFIDENTIAL | MANAGER, **SALES_MANAGER** | el empleado |
| D16, D19, D22, D25 | Nómina y comisiones de EMP001 / 002 / 003 / 004 | EMPLEADOS | RESTRICTED | MANAGER | el empleado |
| D26-D29 | Manuales de seguros (auto, hogar, vida, salud) | GENERAL | INTERNAL | todos los roles | — |
| G01-G03 | Conducta, seguridad de la información, gastos y vacaciones | GENERAL | INTERNAL | todos los roles | — |

32 documentos: GENERAL 7, DIRECCION 6, FINANZAS 7, EMPLEADOS 12.

Notas:
- Resultado por rol sobre READ/DOWNLOAD: MANAGER 32 documentos; ACCOUNTANT 14 (D07-D13 y los 7 generales); SALES_MANAGER 13 (D06, D11, D15, D18, D21, D24 y los 7 generales); cada EMPLOYEE 10 (sus 3 propios y los 7 generales).
- **Consecuencia de unir READ y DOWNLOAD**: SALES_MANAGER puede *descargar* D11 y la actividad comercial, no solo leerlos (en la matriz anterior solo tenía READ). El visor ya entrega el fichero completo al navegador, así que la diferencia era de trazabilidad, no una barrera técnica. Decisión del propietario si se quiere volver a separar: [DECISIONES](../DECISIONES.md#pendientes-para-el-propietario).
- D11 contiene comisiones de **todos**: un empleado no lo lee (ve las suyas en su nómina y con la herramienta `get_commissions`, alcance propio).
- Un empleado nunca ve documentos de otro empleado, aunque sean del mismo departamento.

## Asistente único y fuentes
Un solo asistente; el backend deriva del **rol del JWT** qué KBs de Dify consulta y qué alcance tienen las herramientas (perfil en configuración; ver `assistant_profile` en el YAML).

| Rol | KBs consultadas | Filtro de metadatos enviado a Dify | Alcance de herramientas SQL |
|---|---|---|---|
| MANAGER | General, Direccion, Finanzas, Empleados | ninguno (ve todo) | GLOBAL |
| ACCOUNTANT | General, Finanzas | `allowed_roles` contiene ACCOUNTANT | Primas y pólizas agregadas; comisiones globales; sin objetivos |
| SALES_MANAGER | General, Direccion, Finanzas, Empleados | `allowed_roles` contiene SALES_MANAGER | TEAM (los 4 empleados) |
| EMPLOYEE | General, Empleados | `allowed_roles` contiene EMPLOYEE **o** `owner_id` = usuario | OWN (solo lo propio) |

Las KBs son una cota superior: que SALES_MANAGER consulte KB-Finanzas no le da D07, solo D11, porque lo decide `allowed_roles` y se re-autoriza cada fragmento.

## Casos de prueba obligatorios (negativos)
Se comprueban en REST, listados, recuperación RAG, citas, herramientas y conversaciones. Identificadores en [permissions-mapping §4](../data-model/permissions-mapping.md).
- EMP001 → D17/D18/D19 y cualquier documento de otro empleado (read, download, chat, SQL): denegado.
- EMP001 → D11, D01-D13 salvo los generales: denegado. EMP001 → conversación de EMP002: 404.
- ACC001 → D02, D04, D05, contrato y nómina de cualquier empleado (D14-D25): denegado.
- ACC001 → salarios individuales por chat o SQL: sin datos (no existe herramienta que los devuelva).
- COM001 → D01-D05 y contratos/nóminas: denegado.
- Cualquiera → documento con ID modificado en la URL: 404.
- Cualquiera → petición con `employee_id`/`role`/`assistantId` ajeno: se ignora; el resultado es el del JWT.
- Rol retirado de `allowed_roles` (o cambio de propietario) → el documento deja de aparecer en listados, citas y recuperación, sin reindexar.
- Metadatos de Dify desactualizados (p. ej. `allowed_roles` antiguo en el índice) → el fragmento se descarta en la re-autorización.
- Cualquiera → conversación ajena: 404 (ni MANAGER lee conversaciones de otros).
