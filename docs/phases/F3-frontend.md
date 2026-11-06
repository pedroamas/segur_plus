# F3 — Frontend

**Objetivo:** cuatro pantallas que muestran el backend sin decidir nada, servidas por HTTPS en la LAN. El chat es visible pero **«no disponible»** hasta F5.

**Entregables**
- React + TypeScript + Vite + React Router; tipos coherentes con el OpenAPI (`/v3/api-docs`); el access JWT **solo en memoria** (ni `localStorage` ni `sessionStorage`); renovación automática con `POST /api/v1/auth/refresh` (cookie HttpOnly) ante un 401, con **un único reintento** y cola para las peticiones concurrentes; logout con `/auth/logout`; al recargar la página se intenta restaurar la sesión por refresh.
- **Login**.
- **Dashboard**: pocas cifras según el rol del token (primas, comisiones, pólizas por estado, cumplimiento de objetivos, gastos del mes solo para ACCOUNTANT) con selector de mes. Lo sirve `GET /api/v1/dashboard?month=YYYY-MM` (ver «Backend del dashboard»).
- **Documentos**: listado paginado con los filtros que **soporta el API** (`kb`, `category`, `fileType`), **visor PDF** (pdf.js sobre `/view`), **preview de Excel** (el JSON de `/preview` —texto plano ya calculado, nunca HTML— renderizado como una tabla por hoja) y descarga (`/download`). El API **no** filtra por clasificación ni busca por título: la UI no lo inventa (la clasificación se muestra como columna). Si hace falta, se añadirá al backend en otra fase.
- **Chat**: pantalla visible en la navegación y marcada como **«no disponible»**. No realiza ninguna llamada a endpoints de chat. El chat real llega en [F5](F5-conectar-chat.md), cuando F4 lo haya construido en el backend.
- Fuera: pantallas de arquitectura de IA, de evaluación, analítica aparte y perfil; diseño visual elaborado.
- La UI **nunca decide permisos**: solo muestra lo que devuelve el API (un 404/403 se enseña como mensaje). Sin lógica de autorización en el cliente.

**Backend del dashboard** (antes iba en la fase de Dify; los cambios son mínimos y viven en el paquete `data`)
- Entidades y repositorios de **lectura** para `policies`, `commissions`, `sales_targets` y `expenses` (solo agregado), y servicios `SalesQuery`, `CommissionQuery`, `PolicyQuery`, `TargetQuery` (y `ExpenseQuery`) que reciben un `Subject` y aplican el **alcance por rol** en un único sitio (`DataScope`). El controlador no contiene lógica de alcance. **Las herramientas del chat de [F4](F4-dify-y-chat.md) reutilizan estos servicios** ([ADR 0005](../adr/0005-herramientas-tipadas.md)).
- `GET /api/v1/dashboard?month=YYYY-MM`: el alcance sale del token, **nunca** de parámetros de identidad.

| Rol | Primas, comisiones, pólizas por estado | Cumplimiento de objetivos | Gastos del mes |
|---|---|---|---|
| MANAGER | globales | por empleado + total | — |
| ACCOUNTANT | globales | **no** | suma de `expenses.amount` |
| SALES_MANAGER | equipo comercial (los 4 empleados) | por empleado + total del equipo | — |
| EMPLOYEE | solo lo propio | solo el propio | — |

- Mes por defecto: el último mes cerrado respecto al `Clock` de negocio (2027-01-15 ⇒ 2026-12). `month` solo admite meses con datos (2026-09 … 2026-12); otro valor ⇒ 400.
- Definiciones: primas = suma de `policies.premium` con `sale_date` en el mes; pólizas por estado = pólizas vendidas en el mes según su estado actual; comisiones = `ACCRUAL` del mes (el `CLAWBACK` negativo del mes se devuelve aparte y también el neto); cumplimiento = primas del empleado ÷ objetivo del mes.
- Octubre de 2026 (MANAGER): 12 pólizas, 9.680,00 € de primas, 968,00 € de comisiones devengadas, cumplimientos 105,0 / 76,0 / 73,2 / 68,8 % y 80,7 % total. `monthly_salary` no aparece nunca.

**Servicio por HTTPS (solo LAN)**
- Contenedor **Caddy** en `deploy/` (servicio `web` de `docker-compose.yml`), `tls internal` (CA local de Caddy), ligado a `192.168.0.28:5174` (no a `0.0.0.0`). Sirve `frontend/dist` y hace `reverse_proxy` de `/api/*` al backend del host (`host.docker.internal:8100`): **mismo origen, sin CORS**, y la cookie `Secure; SameSite=Strict` del refresh funciona. `flush_interval -1` queda preparado para el SSE de F5.
- El navegador mostrará un aviso por el certificado propio; se acepta una vez.
- **No exponer a internet:** el propietario **no debe** abrir ni reenviar el puerto 5174 en el router.
- Cómo reconstruir y reiniciar: ver el README del frontend (`frontend/README.md`).

**Aceptación (medible)**
1. Los 7 usuarios inician sesión y ven el dashboard de su rol; ninguna cifra ajena; octubre exacto para DIR001 (prueba E2E en navegador real contra `https://192.168.0.28:5174`).
2. En cada rol, la lista de documentos del UI coincide con la matriz (7 comprobaciones E2E contra el oráculo `permissions-spec.yaml`).
3. PDF y Excel se visualizan; la descarga funciona solo donde la matriz lo permite (documento ajeno ⇒ mensaje de no encontrado).
4. El bundle del frontend y las respuestas de red **no contienen** ninguna clave (Dify, Gemini, JWT, MinIO): búsqueda automatizada con los valores de `deploy/.env`.
5. Tras caducar el access token, la sesión se renueva sin pedir login; tras logout, no (prueba E2E).
6. La pantalla de chat muestra el estado «no disponible» y **no realiza peticiones de chat**.

**Tests de backend:** alcance del dashboard por cada uno de los 7 usuarios, cifras de octubre, mes inválido (400), `monthly_salary` ausente (ampliando `MonthlySalaryNotExposedTest`), EMPLOYEE no ve cifras ajenas aunque envíe parámetros; ArchUnit sigue en verde.

**Fuera de alcance:** diseño visual elaborado; Dify y chat real.
