# ADR 0005 — Herramientas tipadas que reutilizan `data`

Estado: aceptado (revisado en la rama `simple`). **Reemplaza** «Herramientas SQL tipadas» de F0 (10 herramientas) por un catálogo de 4 + búsqueda documental.

## Contexto
Las preguntas sobre ventas, comisiones, pólizas u objetivos deben salir de PostgreSQL, no de fragmentos de Excel/CSV, y no pueden filtrar datos de otros usuarios. Text-to-SQL es incompatible con el principio de que el LLM no ejecuta SQL libre.

## Decisión
1. **Nunca SQL libre.** El LLM solo propone una herramienta del catálogo y **parámetros tipados**; el backend valida, autoriza y ejecuta.
2. **Reutilización**: cada herramienta es una fachada sobre un servicio de `data` (`SalesQuery`, `CommissionQuery`, `PolicyQuery`, `TargetQuery`). Los mismos servicios alimentan el dashboard: una sola implementación de cada cifra y un solo sitio donde se aplica el alcance.
3. **El alcance sale del `Subject`**, no de parámetros: ninguna herramienta tiene `employee_id`, `role`, `assistantId` ni `datasetId`; si el LLM los propone se descartan. Un filtro opcional `employee_code` solo existe para SALES_MANAGER/MANAGER y se comprueba contra su alcance (fuera ⇒ resultado vacío). EMPLOYEE no lo tiene.
4. **Parámetros tipados**: periodo (`MONTH:2026-10`, `THIS_MONTH`, `LAST_MONTH`, `YEAR_TO_DATE`), `group_by` de lista cerrada, `product` ∈ {AUTO, HOGAR, VIDA, SALUD}, `status` ∈ {ACTIVE, CANCELLED, EXPIRED}. «Este mes» lo resuelve el backend con el reloj de negocio (ADR 0006).
5. **Salida**: `{tool, params_resueltos, as_of, currency, rows[], totals}`; importes como cadenas decimales con moneda; límite de filas.
6. **Sin escritura.** Cada llamada pasa por `AccessPolicyService` y se audita (`TOOL_CALL`, metadatos); se guarda en `messages.tools`.

### Catálogo
| Herramienta | Qué devuelve | MANAGER | ACCOUNTANT | SALES_MANAGER | EMPLOYEE |
|---|---|---|---|---|---|
| `get_sales_summary` | Ventas y primas por periodo, agrupadas por empleado, producto o aseguradora | GLOBAL | AGGREGATE | TEAM | OWN |
| `get_commissions` | Comisiones de empleado por periodo | GLOBAL | GLOBAL | TEAM | OWN |
| `get_policies_by_status` | Pólizas por estado | GLOBAL | AGGREGATE | TEAM | OWN |
| `get_targets` | Objetivos y cumplimiento | GLOBAL | — | TEAM | OWN |
| `search_documents` | Recuperación documental (ADR 0003) | sus KBs | sus KBs | sus KBs | sus KBs |

Sin herramientas para gastos, facturas, clientes ni nóminas: gastos y facturas se responden desde los documentos (D07, D12, D13); las nóminas nunca por herramienta (`monthly_salary` no sale de la BD salvo para generar documentos).

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Text-to-SQL con usuario de solo lectura | Un prompt injection podría pedir columnas o filas fuera de alcance |
| Vistas con variable de sesión (RLS) como control principal | El alcance en Java es más testeable |
| Cifras desde fragmentos RAG de Excel/CSV | No fiables (tablas troceadas) |
| Herramientas con `employee_id` como parámetro | El LLM elegiría la identidad |

## Consecuencias
- (+) Superficie pequeña y enumerable; el dashboard y el chat dan la misma cifra (CA-7).
- (−) Preguntas sobre gastos o facturas dependen del texto de los documentos; el propietario decidió **no** añadir una herramienta de gastos.
