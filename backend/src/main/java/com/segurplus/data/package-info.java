/**
 * Datos de negocio de solo lectura (policies, commissions, sales_targets y expenses agregado). Los servicios
 * ({@code SalesQuery}, {@code CommissionQuery}, {@code PolicyQuery}, {@code TargetQuery}, {@code ExpenseQuery}) reciben un
 * {@link com.segurplus.auth.Subject} y aplican el alcance por rol en un único sitio ({@code DataScopeResolver}); el
 * controlador del dashboard ({@code data.api}) no contiene lógica de alcance. Las herramientas del chat (F4) reutilizan
 * estos mismos servicios. Todos los importes se agregan como EUR (única moneda del dataset). {@code monthly_salary} no
 * está mapeada en ninguna entidad.
 */
package com.segurplus.data;
