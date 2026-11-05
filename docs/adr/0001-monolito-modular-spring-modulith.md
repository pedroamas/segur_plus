# ADR 0001 — Monolito modular con Spring Modulith

Estado: propuesto (F0) · Fecha de negocio: 2026-11-05

## Contexto
SegurPlus es un portfolio de un solo equipo, con 14 módulos de dominio y un despliegue por `docker compose`. Necesita demostrar fronteras de arquitectura **verificables**, una sola unidad de transacción (venta → póliza → comisión → asiento) y una política de acceso única que atraviese REST, IA y RAG. El brief admite «Spring Modulith (o ArchUnit)»; CLAUDE.md dice «Spring Modulith/ArchUnit».

## Decisión
Un único despliegue Spring Boot (Java 21) organizado en módulos `auth, users, departments, documents, security, ai, analytics, sales, customers, insurance, finance, hr, audit, shared`, con `com.segurplus.<modulo>/{api,application,domain,infrastructure}`.

- **Spring Modulith** verifica el grafo de dependencias (acíclico, [03-components](../architecture/03-components.md)) con `ApplicationModules.verify()` en CI, y documenta los módulos. Para los eventos entre módulos se usan eventos de aplicación con el registro de publicaciones de Modulith.
- **ArchUnit** complementa con reglas que Modulith no cubre: capas dentro del módulo, «las entidades JPA no salen de `api`», «ningún repositorio público», «sin `Instant.now()`/`LocalDate.now()` fuera de `shared`» (ADR 0006) y «ningún log de `message`/`content`».
- Un módulo solo usa la API pública de otro (paquete raíz o `api`); las dependencias circulares se rompen con eventos o SPI (tabla en 03-components).
- Una sola base PostgreSQL; cada módulo es dueño de sus tablas ([schema.md](../data-model/schema.md)). Las FK entre tablas de módulos distintos existen en BD por integridad, pero el **código** de un módulo no toca tablas ajenas: usa su API.
- `AiGateway` es un puerto del módulo `ai` con adaptador Dify, para poder sustituirlo (p. ej. Spring AI + pgvector) sin tocar el resto.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Microservicios | Coste operativo y transaccional sin beneficio para 7 usuarios; dificulta que una sola política de acceso gobierne todo y que el seed determinista deje datos coherentes |
| Monolito en capas sin módulos | No demuestra fronteras; los ciclos aparecen solos |
| Solo ArchUnit | Funciona, pero Modulith da verificación de módulos, eventos y documentación con menos reglas manuales |
| Multi-módulo Maven (un jar por módulo) | Refuerza fronteras en compilación pero multiplica la configuración; se puede adoptar más tarde si hiciera falta |

## Consecuencias
- (+) Una transacción puede abarcar venta, póliza y comisión; los tests de integración con Testcontainers cubren el flujo entero.
- (+) Extraer un módulo (p. ej. `ai`) es posible: sus únicas dependencias entrantes son por API/eventos.
- (−) La disciplina depende de los tests de arquitectura: una dependencia nueva falla CI hasta justificarse.
- (−) Compartir una BD facilita el atajo SQL entre módulos; se mitiga con la regla de repositorios no públicos.
- Definición de hecho: sin avisos de `verify()`.
