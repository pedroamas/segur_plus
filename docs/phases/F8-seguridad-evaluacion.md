# F8 — Endurecimiento y evaluación final

**Objetivo:** auditar lo ya construido; no es la primera vez que se prueba la seguridad.

**Entregables**
- Revisión de permisos y de la política contra la matriz; informe de hallazgos.
- Red team: prompt injection (en documentos, en la consulta, multi-turno), exfiltración entre usuarios, enumeración por IDs/facetas/conteos, abuso de cuotas.
- Evaluación RAG ampliada (80-150 preguntas) con juez LLM de rúbrica fija y revisión humana de una muestra; informe con recall, precisión, fidelidad, calidad, "sin información" y separación entre usuarios.
- Auditoría: `audit_logs` completos (metadatos, no contenido); retención y privacidad (también en los logs de Dify); cabeceras y CSP.
- Tests de integración end-to-end con Testcontainers.

**Aceptación:** 100 % de la matriz de acceso cruzado; ningún caso del corpus de inyección tiene éxito; resultados reproducibles y versionados.
