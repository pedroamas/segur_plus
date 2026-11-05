# F5 — Seguridad y portfolio

**Objetivo:** cerrar las pruebas de seguridad y dejar el proyecto reproducible y presentable.

**Entregables**
- Batería completa de **acceso cruzado** (EMP↔EMP, ACC↔Dirección, COM↔Dirección/nóminas, conversaciones ajenas) por todos los canales: REST, listados, RAG, citas, herramientas, descargas y chat ([permissions-mapping §4](../data-model/permissions-mapping.md)).
- **Prompt injection**: corpus de documentos con instrucciones maliciosas (indexados en una prueba aparte) y consultas hostiles; ninguna exfiltración ni cambio de alcance.
- Comprobación de logs: sin JWT, prompts ni contenido; auditoría con metadatos.
- `docker-compose.yml` completo (postgres, minio, dify, backend, frontend) con `.env.example`; arranque con seed.
- CI que ejecuta los tests de backend y de arquitectura (ArchUnit opcional).
- README con arquitectura, cómo ejecutar, decisiones y **capturas** (login, documentos, chat con citas, dashboard).

**Aceptación (medible)**
1. Matriz de acceso al **100 %** en todos los canales; 0 accesos cruzados.
2. Corpus de inyección: **0** casos con éxito.
3. `docker compose up` desde un clone limpio + seed deja operativo el sistema (checklist de 7 logins y 1 pregunta por rol).
4. Evaluación de F3 repetida sobre la build final sin regresión (recall@5 ≥ 0,8; citas 100 % válidas).
5. README y ADR suficientes para que otra persona arranque el proyecto sin ayuda.

**Mejoras opcionales (no necesarias):** reranking y búsqueda híbrida, refresh con rotación, reglas de contexto, RLS de PostgreSQL, antivirus de subida, `get_expenses`.
