# F2 — Empresa y dataset

**Objetivo:** datos coherentes y 29 (+3) documentos generados desde la BD.

**Entregables**
- Generador determinista (semilla fija) que puebla CRM, pólizas, finanzas, HR y produce PDF, XLSX (hojas, fórmulas, totales), CSV y JSON.
- Mes ancla octubre 2026 con las cifras del brief §6; septiembre, noviembre y diciembre con estacionalidad. Reloj ficticio 2027-01-15.
- Los 29 documentos del brief + 3 generales, con las imperfecciones deliberadas y respetando A5 (nóminas agregadas en Contabilidad).
- Documento con prompt injection, **fuera** del corpus principal.
- Metadatos de cada documento (clasificación, propietario, departamento, tipo) listos para indexar.

**Tests obligatorios:** consistencia automática (cada cifra del documento = cálculo de BD); relaciones obligatorias del brief §6; reproducibilidad (misma semilla → mismos ficheros).

**Aceptación:** octubre = 12 pólizas, 9.680 €, 968 €, 7.250 € gastos; objetivos y cumplimientos según §6; sin contenido que contradiga la matriz de permisos.

**Fuera de alcance:** subida a MinIO y Dify.
