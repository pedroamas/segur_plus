# F2 — Datos y documentos: generador determinista y seed

**Objetivo:** un dataset coherente por construcción (la BD es la fuente de verdad) y los 32 documentos generados desde ella.

**Entregables**
- Carpeta `generator/`: semilla fija; genera `users` (7, con salario mensual), clientes, pólizas (la venta es la póliza; **150-300 altas nuevas** de septiembre a diciembre de 2026, sin cartera previa; cancelaciones y renovaciones solo de esas pólizas), comisiones, facturas, gastos y objetivos. Mes ancla octubre de 2026 con los números del brief.
- Generador de los **29 documentos** (PDF, XLSX, CSV, JSON) + 3 generales desde la BD; contratos y nóminas se generan **desde `users`** (no hay tablas de RR. HH.); `allowed_roles` y `owner_id` salen de `permissions-spec.yaml`.
- Imperfecciones deliberadas y declaradas (partidas sin conciliar, dos versiones de un informe como documentos distintos, diferencia de redondeo, par de documentos casi idéntico con distinta clasificación) siempre dentro del modelo de permisos.
- Seed: carga la BD y sube los ficheros a MinIO con checksum; comando de administración reproducible.
- Test de consistencia: cada cifra de cada documento = cifra calculada en BD.

**Aceptación (medible)**
1. Octubre de 2026: **12 pólizas, 9.680,00 € de primas, 968,00 € de comisiones, 7.250,00 € de gastos, objetivos 12.000,00 € y cumplimientos 105,0 / 76,0 / 73,2 / 68,8 % (80,7 % total)**, exactos.
2. Dos ejecuciones del generador con la misma semilla producen ficheros idénticos (mismos SHA-256).
3. Test cifra documento = BD en verde para los documentos con cifras (D01, D03, D06-D13, D15-D16, D18-D19, D21-D22, D24-D25).
4. 32 documentos sembrados: GENERAL 7, DIRECCION 6, FINANZAS 7, EMPLEADOS 12; la matriz de F1 sigue en verde con los datos reales.
5. Ningún dato con fecha posterior a 2027-01-15 salvo fechas de renovación.
6. `monthly_salary` no aparece en ningún DTO, endpoint ni herramienta (test).

**Fuera de alcance:** Dify y chat.

## Notas de implementación (realidad frente al diseño)
- **Volumen:** 164 altas (septiembre 24, **octubre 12**, noviembre 56, diciembre 72), dentro de las 150-300.
- **Formatos** (el YAML no los declara): PDF 20, XLSX 6 (D02, D07, D08, D10, D11, D12), CSV 5 (D09 y la actividad comercial D15/18/21/24), JSON 1 (D03).
- **Imperfecciones** declaradas en `generator/IMPERFECCIONES.md`; las dos versiones del informe de octubre son D13 (v1.0, borrador) y D01 (v2.0); el par casi idéntico con distinta clasificación es D02/D03.
- **Parámetros derivados** no presentes en la BD (cotizaciones, IRPF, aportación de socios): ver `generator/README.md`.
- **Fixtures de F1:** el seed los reemplaza; el backend no los vuelve a sembrar con `SEGURPLUS_SEED_DOCUMENTS_SPEC_PATH` vacío (valor por defecto de `.env.example`).
- Los documentos adversariales (`generator/adversarial/`) no se siembran.
