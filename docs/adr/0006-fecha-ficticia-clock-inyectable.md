# ADR 0006 — Fecha ficticia y `Clock` inyectable

Estado: aceptado (revisado en la rama `simple`: un único `Clock` inyectable en lugar de dos relojes). Supuesto A2: «hoy» = **2027-01-15**, datos de septiembre a diciembre de 2026.

## Contexto
Diciembre de 2026 es futuro real; sin control, «este mes» lo resolvería la fecha real del sistema o del LLM. La seguridad (expiración del JWT) sí necesita tiempo real.

## Decisión
1. Un bean `java.time.Clock` en `shared`, con la **fecha de negocio** configurable (`segurplus.clock.fixed-date`, por defecto `2027-01-15`; zona `Europe/Madrid`; modo `SYSTEM` posible). Se usa para «hoy», «este mes», periodos, `created_at` de dominio, `audit_logs.business_date` y la fecha que se pasa al asistente (`{{today}}`).
2. La **expiración del JWT** usa el tiempo real del sistema (`Instant.now()` solo en la clase de emisión/validación de tokens, dentro de `auth`; la única excepción a la regla 3). `audit_logs.occurred_at` es tiempo real (`DEFAULT now()`).
3. Nadie más usa `Instant.now()`, `LocalDate.now()` ni `now()` de BD para lógica; se inyecta el `Clock`. Regla ArchUnit opcional (ADR 0001).
4. El asistente no resuelve fechas relativas: la fecha entra en el prompt y las herramientas reciben periodos ya resueltos (ADR 0005).
5. **Tests**: se sustituye el `Clock` (`Clock.fixed(...)`) para probar «este mes» y periodos. Un test del seed comprueba que ningún dato se genera con fecha posterior a la de negocio salvo lo explícitamente futuro (fechas de renovación).
6. Cambiar la fecha ficticia es solo configuración; los datos del seed son absolutos.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Fecha real y datos relativos | El dataset dejaría de ser determinista y los números ancla de octubre cambiarían |
| Dejar al LLM resolver «este mes» | Su fecha real ≠ 2027-01-15 |
| Dos relojes inyectables (versión F0) | Más disciplina de la necesaria; basta con aislar el JWT |

## Consecuencias
- (+) Resultados deterministas; «este mes» = enero de 2027 en toda la aplicación.
- (−) La expiración del JWT es la excepción explícita a la regla del `Clock`; se documenta y se prueba aparte.
