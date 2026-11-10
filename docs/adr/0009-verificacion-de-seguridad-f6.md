# ADR 0009 — Verificación de seguridad de F6 y empaquetado local

Estado: aceptado (rama `f6-seguridad`). Completa los ADR 0002, 0003 y 0007; no cambia el modelo de permisos.

## Contexto
F6 cierra el portfolio: hay que **demostrar** (no solo afirmar) que no hay accesos cruzados, que la inyección de prompts no consigue nada y que el refresh y los logs son seguros; y dejar el proyecto reproducible. Restricciones del propietario: todo **local** (LAN, nada en internet), **sin GitHub**, y modelo en **capa gratuita** de Gemini (≈20 peticiones/día y 5/min por modelo; ≈25 llamadas de presupuesto para toda la fase).

## Decisión
1. **Las pruebas de seguridad usan Dify y modelo simulados** (WireMock `FakeDify`), en el **peor caso**: Dify devuelve el corpus entero ignorando los filtros y el planificador/modelo son **obedientes** y hostiles. Un modelo simulado no mide si un LLM real obedece una orden escondida, pero sí mide lo que importa estructuralmente: que, obedezca o no, el sistema **no le entrega** nada no autorizado, no ejecuta nada fuera del catálogo y no deja que su texto falsifique fuentes o eventos. La obediencia real se muestrea con **2 casos** reales (`evaluation/injection/run_real.py`).
2. **Batería de acceso cruzado única** (`CrossAccessBatteryTest`): cuenta comprobaciones por canal, exige 0 fallos, comprueba con el oráculo independiente del YAML y escribe `target/security-reports/cross-access.md`. Se validó con **mutación** (política «permitir todo») que puede fallar. La doble implementación de la política (predicado y `Specification`) es defensa en profundidad deliberada.
3. **Corpus de inyección versionado** (`evaluation/injection/corpus.json`, 12 documentos + 8 consultas) y **definición de «éxito del ataque»** con 9 predicados (S1-S9, ver `PromptInjectionTest`). Los documentos maliciosos se **suben por la API** y se indexan en una prueba con BD de usar y tirar: el dataset de 32 documentos no se contamina. El caso real sube sus documentos solo para MANAGER (y EMP002) y los **borra siempre** (Dify, MinIO, BD, auditoría).
4. **Hallazgo F6-01 corregido**: el título del documento va saneado en la cabecera del bloque de datos del prompt (`PromptBuilder.headerText`).
5. **Sin GitHub**: la CI es `.github/workflows/ci.yml` (no se ejecuta aquí) más `scripts/ci-local.sh`, que ejecuta **los mismos pasos** (el workflow llama al script paso a paso, así no pueden divergir). Los E2E reales de Playwright **no** forman parte de la CI (gastan modelo y necesitan el stack vivo).
6. **Compose completo, Dify fuera.** `deploy/docker-compose.yml` incluye postgres, minio, backend, web y un servicio `seed` de un solo uso en un perfil (el seed **resetea** datos: nunca se ejecuta en un `up` normal). Dify es una instancia aparte (`DIFY_BASE_URL`; `scripts/check-dify.sh` la verifica sin tocarla). Un perfil opcional `mockdify` (Dify simulado en Python) permite arrancar y probar sin Dify ni Gemini. Los puertos y el nombre de proyecto son variables con los valores de la instancia viva por defecto, y la instancia con el backend en el host usa una superposición (`docker-compose.host-backend.yml`).
7. **Prueba de clone limpio automatizada** (`scripts/clone-test.sh`): clona el commit, reserva puertos en el Port Panel, usa proyecto/datos/puertos propios y Dify simulado, siembra, verifica 7 logins + 1 pregunta por rol + un cruce, desmonta y comprueba que la instancia viva no cambió.
8. **Evaluación real reducida con honestidad**: con la cuota gratuita solo se midieron 10 de las 32 preguntas (recall@5, citas válidas, fugas); el resto se declara «no medido» en lugar de extrapolar.

## Alternativas
| Alternativa | Por qué no |
|---|---|
| Probar la inyección solo con el modelo real | Cuota insuficiente, no determinista y no reproducible en CI; el simulado obediente es el peor caso reproducible |
| Probar la inyección solo con el simulado | No dice nada de la obediencia real: por eso 2 casos reales |
| Indexar los documentos maliciosos en los datasets reales para la prueba simulada | Contamina el dataset de 32 documentos y la evaluación; la prueba apartada usa BD y Dify de usar y tirar |
| Incluir Dify en el compose | Instancia compartida, editada a mano, con sus claves y puertos: no se toca |
| CI solo como YAML | No se puede ejecutar aquí ni comprobar; el script local garantiza los mismos pasos |
| RLS de PostgreSQL, antivirus de subida, reranking | Mejoras opcionales fuera del alcance; no se hicieron |

## Consecuencias
- (+) Los números de seguridad son reproducibles con `./mvnw verify` sin red ni Gemini.
- (+) Un clone limpio se levanta con un comando y una checklist automática.
- (−) La prueba simulada **no** demuestra que un LLM concreto resista la inyección; solo que el sistema no depende de ello.
- (−) La evaluación real de F6 es un subconjunto (10/32); la cifra completa sigue siendo la de F4 (32/32, recall 18/18).
- (−) El `mockdify` ignora filtros y no genera lenguaje natural: sirve para el cableado, no para calidad.
