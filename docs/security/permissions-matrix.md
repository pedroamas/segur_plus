# Matriz de permisos (borrador)

Convenciones. `R` = READ, `D` = DOWNLOAD, `—` = sin acceso. `owner` = el empleado propietario. Cualquier acceso no listado está denegado.
Clasificación: `PUBLIC < INTERNAL < CONFIDENTIAL < RESTRICTED`.
Supuestos A3 y A5 del brief aplicados (KB-Sales nueva; nóminas agregadas en Contabilidad).

## Documentos
| ID | Documento | KB | Clasif. | MANAGER | ACCOUNTANT | SALES_MANAGER | EMPLOYEE |
|---|---|---|---|---|---|---|---|
| D01 | Informe mensual de dirección | Management | CONFIDENTIAL | R D | — | — | — |
| D02 | Presupuesto anual 2026 (incluye salarios) | Management | RESTRICTED | R D | — | — | — |
| D03 | Cuadro de mando KPI | Management | CONFIDENTIAL | R D | — | — | — |
| D04 | Plan estratégico 2026-2028 | Management | RESTRICTED | R D | — | — | — |
| D05 | Actas comité de dirección | Management | RESTRICTED | R D | — | — | — |
| D06 | Rendimiento comercial (CRM) | Sales | CONFIDENTIAL | R D | — | R D | — |
| D07 | Facturas emitidas y recibidas | Finance | CONFIDENTIAL | R D | R D | — | — |
| D08 | Libro mayor (nóminas agregadas) | Finance | CONFIDENTIAL | R D | R D | — | — |
| D09 | Extracto bancario (nóminas agregadas) | Finance | CONFIDENTIAL | R D | R D | — | — |
| D10 | Conciliación bancaria | Finance | CONFIDENTIAL | R D | R D | — | — |
| D11 | Liquidación de comisiones (todos los empleados) | Finance | CONFIDENTIAL | R D | R D | R | — |
| D12 | Registro de gastos | Finance | CONFIDENTIAL | R D | R D | — | — |
| D13 | Informe de cierre contable | Finance | CONFIDENTIAL | R D | R D | — | — |
| D14-16 | EMP001: contrato / actividad comercial / nómina y comisiones | EMP001 | RESTRICTED / CONFIDENTIAL / RESTRICTED | R D | — | solo actividad: R | owner: R D |
| D17-19 | EMP002 (igual) | EMP002 | ídem | R D | — | solo actividad: R | owner: R D |
| D20-22 | EMP003 (igual) | EMP003 | ídem | R D | — | solo actividad: R | owner: R D |
| D23-25 | EMP004 (igual) | EMP004 | ídem | R D | — | solo actividad: R | owner: R D |
| D26-29 | Manuales de seguros (auto, hogar, vida, salud) | Insurance | INTERNAL | R D | R D | R D | R D |
| G01-03 | Documentos generales (conducta, seguridad, gastos/vacaciones) *(nuevos)* | Company | INTERNAL | R D | R D | R D | R D |

Notas:
- La actividad comercial del equipo es visible para el responsable comercial; contratos y nóminas no (contienen salario).
- D11 contiene comisiones de **todos**: un empleado no puede leerlo (ve las suyas en su nómina y vía SQL).
- Un empleado nunca ve documentos de otro empleado, aunque sean del mismo departamento.

## Asistentes y fuentes
| Asistente | Usuario | KBs | Herramientas SQL (alcance) |
|---|---|---|---|
| Management | DIR001 | Management, Sales, Finance, Insurance, Company, KB-EMP* (RESTRICTED incluido) | Todas, alcance global |
| Accounting | ACC001 | Company, Finance, Insurance | Finanzas globales; **sin** salarios individuales |
| Sales | COM001 | Company, Insurance, Sales + actividad comercial de empleados (D16, D19, D22, D25) | Ventas, objetivos y comisiones del equipo |
| Employee | EMP00X | Company, Insurance, KB-EMP00X (propia) | Solo datos propios (alcance por JWT) |

## Reglas de política (ABAC)
1. Deny by default.
2. READ/DOWNLOAD permitido si existe un grant (rol, departamento o usuario) para la acción **y** el nivel de clasificación del recurso ≤ nivel del sujeto, **o** el sujeto es el propietario (`owner_employee_id`).
3. ADMINISTER y CREATE/UPDATE/DELETE solo según rol (a definir en F1; por defecto, solo MANAGER sobre su ámbito).
4. Contexto: descargas masivas, intentos fallidos y horario pueden elevar a DENY o a auditoría reforzada.
5. Una misma regla se evalúa igual en REST, herramientas de IA y recuperación RAG.

## Casos de prueba obligatorios (negativos)
- EMP001 → D17/D18/D19 (read, download, chat, SQL): denegado.
- EMP001 → D11: denegado. EMP001 → conversación de EMP002: 404.
- ACC001 → D02, D04, D05, contrato y nómina de cualquier empleado: denegado.
- ACC001 → salarios individuales por chat o SQL: sin datos.
- COM001 → D01-D05 y nóminas/contratos: denegado.
- Cualquiera → documento con ID modificado en la URL: 404.
- Cualquiera → petición con `employee_id`/`role`/`assistantId` ajeno: se ignora; el resultado es el del JWT.
- Usuario retirado de un grant → el documento deja de aparecer en listados, citas y retrieval, sin reindexar a mano.
