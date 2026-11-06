# Imperfecciones deliberadas (H-18)

Declaradas aquí para que las pruebas de F3-F5 (recuperación, citas, evaluación) sepan qué defectos son **intencionados** y cuáles serían un fallo del dataset. Ninguna altera los números ancla de octubre de 2026 ni el modelo de permisos (`allowed_roles`, `owner` y clasificación salen de `permissions-spec.yaml`). Cada una tiene su test en `tests/test_imperfections.py`.

## 1. Partidas sin conciliar (D10, con D08 y D09)
En la conciliación bancaria de octubre (cuenta 572 a 31/10/2026) quedan tres partidas:

| Partida | Origen | Importe | Estado |
|---|---|---:|---|
| Cobro de la factura `FE-2026-0004` (Seguros Atlántico), contabilizado el 20/10 y abonado por el banco el 02/11 | Libros | 200,06 € | En tránsito (se concilia en noviembre) |
| Transferencia recibida ref. 77412, ordenante sin identificar (28/10) | Banco | 126,50 € | **Sin identificar** (sin contabilizar) |
| Comisión de mantenimiento de cuenta (31/10) | Banco | 18,00 € | **Pendiente de contabilizar** (no es un gasto de `expenses`) |

Saldo banco 138.502,63 € + 200,06 − 126,50 + 18,00 = saldo libros 138.594,19 €: la diferencia no explicada es 0,00 €. Los gastos de octubre (7.250,00 €) no incluyen la comisión bancaria.

## 2. Dos versiones del mismo informe como documentos distintos (D13 y D01)
El informe de resultados de octubre existe como **versión 1.0 (borrador, 06/11/2026)** dentro del informe de cierre contable **D13** (KB-Finanzas; la ven MANAGER y ACCOUNTANT) y como **versión 2.0 (final, 10/11/2026)** en el informe mensual de dirección **D01** (KB-Dirección; solo MANAGER). Mantiene los 32 documentos y los códigos del YAML: D13 aloja el borrador y D01 lo sustituye. Las cifras ancla y de BD son idénticas en ambos; difieren en la **previsión de altas** de noviembre (v1: 40; v2: 55), en el plan de seguimiento y en las conclusiones (solo v2). Una pregunta sobre «la previsión de noviembre» debe citar la versión que el usuario puede leer y, si puede leer ambas, preferir la 2.0.

## 3. Diferencia de redondeo entre dos documentos (D08 y D13)
La cotización a cargo de la empresa de octubre (31,60 % sobre el bruto) se calcula en **D08 y D01 sobre la base agregada** (7.634,87 €) y en **D13 como suma de cuotas individuales** (7.634,88 €): 0,01 € de diferencia, y por tanto 0,01 € en el resultado del mes (−37.540,86 € frente a −37.540,87 €). Ni D08 ni D13 muestran cuotas por persona (A5).

## 4. Par casi idéntico con distinta clasificación (D02 y D03)
El presupuesto 2026 **D02** (RESTRICTED, solo MANAGER, con salarios individuales) y el cuadro de mando **D03** (CONFIDENTIAL, solo MANAGER, JSON) comparten las mismas líneas de ingresos y gastos presupuestados por categoría y mes; D03 solo recoge el personal como importe agregado. Una respuesta basada en D03 no debe revelar sueldos individuales aunque el contenido sea casi el mismo que el de D02. (Ambos son visibles solo por MANAGER, de modo que el par no abre ningún acceso nuevo.)

## 5. Documentos adversariales (fuera del corpus)
`generator/adversarial/` contiene tres ficheros con instrucciones de *prompt injection* embebidas (texto blanco en un PDF, campo de notas de un CSV de CRM, comentario de celda y hoja oculta en un XLSX). **No se siembran en la BD ni se suben a MinIO** (lo comprueba `test_adversarial_documents_are_not_seeded`); son para la batería de F5. Detalle en `adversarial/README.md`.

## Decisiones de modelado que conviene conocer (no son defectos)
- **Ritmo de altas:** 24 (sep), **12 (oct, mes ancla)**, 56 (nov) y 72 (dic) = 164. Octubre es deliberadamente bajo (cambio de CRM y menos marketing, narrado en D01 y D05). Los objetivos de septiembre, noviembre y diciembre se calculan en proporción a las ventas del mes con cumplimientos variados (100,5 / 80,7 / 93,8 / 85,8 %).
- **Pérdidas (A1):** el mes de octubre sale en pérdidas (ingresos 1.505,00 € de comisiones de aseguradoras frente a 7.250,00 € de gastos y 24.160,99 € de sueldos). Los documentos lo explican y lo financian con una aportación de socios de 200.000 €.
- **Fechas de vencimiento:** las facturas con vencimiento natural posterior al 15/01/2027 se registran con `due_date` = 15/01/2027 y estado `OPEN` para respetar «ningún dato posterior a 2027-01-15».
