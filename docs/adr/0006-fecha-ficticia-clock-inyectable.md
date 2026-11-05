# ADR 0006 — Fecha ficticia y `Clock` inyectable (dos relojes)

Estado: propuesto (F0). Supuesto A2 confirmado: «hoy» = **2027-01-15**, datos de septiembre a diciembre de 2026.

## Contexto
Diciembre de 2026 es futuro real; sin control, «este mes» lo resolvería la fecha real del sistema o del LLM. Pero congelar *todo* el reloj rompería la seguridad: expiración del JWT y del refresh, bloqueos de cuenta, reintentos del outbox, plazos y circuit breakers necesitan tiempo real.

## Decisión
Dos relojes inyectables, ambos `java.time.Clock` en `shared`:

| Reloj | Uso | Modo por defecto |
|---|---|---|
| **`BusinessClock`** | «Hoy», «este mes», periodos, caducidad de grants, `business_date` de mensajes, fecha que se pasa al asistente (`promptVars.today`), zona de reglas de horario, generación del dataset | `FIXED` en `2027-01-15` (hora configurable, p. ej. 10:00 `Europe/Madrid`); `SYSTEM` posible por configuración |
| **`SystemClock`** | Expiración de JWT/refresh, `locked_until`, `audit_logs.occurred_at`, `outbox_events`, `refresh_tokens`, timeouts, métricas | siempre tiempo real |

Reglas:
1. **Nadie usa `Instant.now()`, `LocalDate.now()`, `ZonedDateTime.now()` ni `now()` de BD para lógica**: se inyecta un reloj (regla ArchUnit, ADR 0001). El `DEFAULT now()` del DDL es solo una red.
2. `created_at`/`updated_at` de tablas de **dominio** = `BusinessClock` (JPA auditing con un `DateTimeProvider`); de tablas **operativas/seguridad** = `SystemClock` ([schema.md](../data-model/schema.md), «Convenciones»).
3. **Auditoría**: `occurred_at` (sistema) y `business_date` (negocio) se guardan juntos, para correlacionar un evento real con el «día» ficticio. Las particiones mensuales de `audit_logs` siguen el tiempo de sistema.
4. **Zona**: `Europe/Madrid`. Los periodos mensuales se calculan en esa zona.
5. **Asistente**: la fecha de negocio entra en el prompt (`{{today}}`) y en la planificación de herramientas; el LLM no resuelve fechas relativas por su cuenta. Las herramientas reciben períodos ya resueltos (ADR 0005).
6. **Tests**: se sustituye el reloj de negocio (`Clock.fixed(...)`/reloj mutable) para probar caducidad de grants, «este mes», cuotas diarias y rolling de periodos. Un test del seed comprueba que ningún dato se genera con fecha posterior a la de negocio salvo lo explícitamente futuro (renovaciones previstas).
7. Cambiar la fecha ficticia es solo configuración (`segurplus.clock.fixed-date`), sin tocar datos; los datos del seed son absolutos.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Un único reloj fijo | JWT sin caducidad real; bloqueos y reintentos congelados |
| Fecha real y datos relativos («hace 2 meses») | El dataset dejaría de ser determinista y los números ancla de octubre cambiarían |
| Dejar al LLM resolver «este mes» | Fecha real del LLM ≠ 2027-01-15 (justo el problema de A2) |
| Reloj de negocio desplazado (`offset`) que avanza | El «día» cambiaría durante una demo; la fecha fija es más reproducible |

## Consecuencias
- (+) Resultados deterministas y reproducibles; «este mes» = enero de 2027 en toda la aplicación.
- (+) Seguridad con tiempo real; auditable con ambos tiempos.
- (−) Dos relojes obligan a elegir correctamente en cada servicio (revisión y regla ArchUnit).
- (−) Con reloj fijo, la regla de horario (matriz, regla 4) usa la hora de negocio configurada; para probarla hay que cambiarla en el test.
