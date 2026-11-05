# F6 — Analytics y dashboards

**Objetivo:** indicadores por perfil desde consultas SQL controladas, con el mismo servicio que usan las herramientas de IA.

**Entregables**
- `GET /api/v1/analytics/sales` y `/commissions` (parámetros `period`, `groupBy` como enums; `employeeId` solo si la política lo permite).
- Dashboards: Dirección (ventas, ingresos, gastos, comisiones, beneficio estimado, por producto, rendimiento, evolución, objetivos), Contabilidad (facturas pendientes/pagadas, gastos, comisiones, conciliación), Responsable Comercial (equipo, objetivos, conversión de oportunidades, evolución), Empleado (ventas, objetivo, progreso, clientes, pólizas, comisiones).
- Leads y oportunidades en la BD (necesarios para "conversión").

**Tests obligatorios:** alcance por rol (un empleado no obtiene datos de otro por parámetros); cifras = BD; periodos y comparaciones.

**Aceptación:** cada dashboard coincide con los datos de §6 del brief; sin consultas libres.
