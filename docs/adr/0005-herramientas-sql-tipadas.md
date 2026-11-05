# ADR 0005 — Herramientas SQL tipadas que reutilizan `analytics`

Estado: propuesto (F0)

## Contexto
Las preguntas sobre ventas, comisiones, pólizas, gastos u objetivos deben salir de PostgreSQL, no de fragmentos de Excel/CSV, y no pueden filtrar datos de otros usuarios. Dejar que el LLM escriba SQL (text-to-SQL) es incompatible con el principio 7 (superficie de inyección, saltos de alcance, columnas sensibles). El brief habla de «vistas con alcance», pero una vista no conoce al usuario salvo con variables de sesión (H-09).

## Decisión
1. **Nunca SQL libre.** El LLM solo propone una herramienta del catálogo y **parámetros tipados**; el backend valida, autoriza y ejecuta.
2. **Reutilización**: cada herramienta es una fachada fina sobre un servicio de `analytics` (`SalesAnalytics`, `CommissionAnalytics`, `PolicyAnalytics`, `ObjectiveAnalytics`, `FinanceAnalytics`, `PeriodComparison`). Los mismos servicios alimentan los dashboards REST: una sola implementación de cada cifra, un solo sitio donde se aplica el alcance.
3. **El alcance sale del `Subject`**, no de parámetros: ninguna herramienta tiene `employee_id`, `role`, `department`, `assistantId` ni `datasetId`. Si el LLM los propone se descartan. Para consultar «las ventas de Laura» un responsable usa un filtro `employee_code` **dentro de su alcance TEAM** (se resuelve y se comprueba contra el alcance; fuera ⇒ resultado vacío, no error informativo). Un EMPLOYEE no dispone de ese filtro.
4. **Alcance** por asistente (permisos de `role_permissions`, [permissions-mapping §5](../data-model/permissions-mapping.md)): `GLOBAL` (Management), finanzas globales con nóminas solo `AGGREGATE` (Accounting), `TEAM` (Sales), `OWN` (Employee). Se traduce a un predicado en el repositorio vía `Specification` (ADR 0002) o a la vista agregada `v_payroll_monthly_aggregate`.
5. **Parámetros tipados**: fechas ISO o `period` enumerado (`THIS_MONTH`, `LAST_MONTH`, `YEAR_TO_DATE`, `MONTH:2026-10`…), `group_by` de una lista cerrada, `product_line` ∈ {AUTO, HOME, LIFE, HEALTH}, `status` enumerado, `limit` ≤ tope. «Este mes» lo resuelve el backend con el **reloj de negocio** (ADR 0006), y la fecha se pasa al planificador. Esquema validado (Bean Validation/JSON Schema) antes de ejecutar; un parámetro inválido ⇒ error de herramienta, no consulta.
6. **Salida**: `{tool, params_resueltos, as_of, currency, rows[], totals, truncated}`; importes como cadenas decimales con moneda (sin coma flotante); límite de filas; sin columnas fuera de la lista de la herramienta.
7. **Grounding**: toda cifra de la respuesta debe aparecer en la salida de alguna herramienta (paso 6 del flujo). Se guarda `message_tool_calls` (herramienta, argumentos tipados, nº de filas, duración).
8. **Sin escritura**: ninguna herramienta modifica datos.
9. Cada llamada pasa por `AccessPolicyService` (permiso del recurso + alcance) y se audita (`TOOL_CALL`, metadatos).

### Catálogo
Inicial (brief §7): `get_sales_summary`, `get_commissions`, `get_policies`, `compare_periods`, `get_objectives`, `search_documents`. Ampliaciones propuestas (H-14), necesarias para los casos de Contabilidad y Dirección que el brief cita («facturación, gastos, clientes»):

| Herramienta | Tipo | Servicio | Management | Accounting | Sales | Employee |
|---|---|---|---|---|---|---|
| `get_sales_summary` | SQL | SalesAnalytics | GLOBAL | AGGREGATE | TEAM | OWN |
| `get_commissions` | SQL | CommissionAnalytics | GLOBAL | GLOBAL | TEAM | OWN |
| `get_policies` | SQL | PolicyAnalytics | GLOBAL | AGGREGATE | TEAM | OWN |
| `compare_periods` | SQL | PeriodComparison | GLOBAL | finanzas | TEAM | OWN |
| `get_objectives` | SQL | ObjectiveAnalytics | GLOBAL | — | TEAM | OWN |
| `get_expenses` *(nueva)* | SQL | FinanceAnalytics | GLOBAL | GLOBAL | — | — |
| `get_invoices` *(nueva)* | SQL | FinanceAnalytics | GLOBAL | GLOBAL | — | — |
| `get_accounting_summary` *(nueva)* | SQL | FinanceAnalytics | GLOBAL | GLOBAL (nóminas agregadas) | — | — |
| `get_customers` *(nueva)* | SQL | CustomerQuery | GLOBAL | — | TEAM | OWN |
| `search_documents` | RAG | (retrieval, ADR 0003) | sus KBs | sus KBs | sus KBs + actividad | sus KBs |

Nóminas individuales no tienen herramienta (ni para Management): se consultan por documento o REST con la política habitual (H-27).

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Text-to-SQL con usuario de BD de solo lectura | Un prompt injection podría pedir columnas o filas fuera de alcance; el alcance dependería de que el LLM «obedezca» |
| Vistas con alcance + variable de sesión (RLS) como control principal | Posible como defensa en profundidad (F8) pero exige `SET LOCAL` por petición y complica pruebas; el alcance en Java es más testeable |
| Cifras desde fragmentos RAG de Excel/CSV | No son fiables (tablas troceadas, imperfecciones deliberadas) |
| Herramientas con `employee_id` como parámetro | Rompe el principio 7: el LLM elegiría la identidad |

## Consecuencias
- (+) Superficie de ataque pequeña y enumerable; cada herramienta tiene tests de autorización con la matriz de alcance.
- (+) Coherencia: dashboard y chat dan la misma cifra (criterio 8: consistencia con la BD).
- (−) El catálogo limita las preguntas posibles; ampliar exige código. El routing (¿RAG, SQL o ambas?) se evalúa aparte (criterio 7).
- (−) Hay que mantener los esquemas de parámetros sincronizados con los prompts del planificador (`prompt_version`, `ai_tools.schema_version`).
