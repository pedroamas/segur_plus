# generator — datos y documentos de SegurPlus (F2)

Generador **determinista** (semilla fija) del dataset de negocio y de los 32 documentos. PostgreSQL es la fuente de verdad: los documentos se generan **leyendo la BD**, y `allowed_roles`, `owner`, `kb`, clasificación y categoría salen de `docs/data-model/permissions-spec.yaml` (nunca están en el código).

## Puesta en marcha
```bash
cd generator
uv venv .venv && . .venv/bin/activate          # o: python3 -m venv .venv
uv pip install -r requirements.txt             # o: pip install -r requirements.txt
```
Requisitos: infraestructura arriba (`docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`) y el backend arrancado **al menos una vez** (crea las tablas con Flyway y los 7 usuarios). La conexión a PostgreSQL y MinIO se lee de `deploy/.env` (no versionado; nada se imprime ni se escribe en logs).

## Seed (reproducible e idempotente)
```bash
python -m segurplus_gen.seed                 # BD + ficheros (generator/out) + MinIO + tabla documents
python -m segurplus_gen.seed --out /tmp/x    # otro directorio de salida
python -m segurplus_gen.seed --files-only    # solo genera ficheros desde la BD actual (no resetea ni sube)
```
Qué hace, en este orden:
1. **Resetea** `commissions, policies, expenses, invoices, sales_targets, customers` y los vuelve a cargar (no toca `users` salvo `monthly_salary`, ni `audit_logs`, ni `refresh_tokens`, ni conversaciones).
2. Genera los 32 ficheros en `out/` (+ `manifest.json` con SHA-256) y los 3 adversariales en `adversarial/`.
3. Sube a MinIO (bucket `segurplus-documents`, clave `documents/<uuid>.<ext>`, igual que el backend) y **elimina los objetos que ya no están en la BD** (fixtures de F1 y subidas de prueba).
4. Sustituye **todas** las filas de `documents` (checksum SHA-256, `index_status = PENDING`, `owner_id` buscado por `employee_code`, `created_by = DIR001`).

Idempotente: los ids son `uuid5` y las marcas de tiempo usan el reloj de negocio (2027-01-15), así que dos seeds seguidos dejan **exactamente** el mismo estado (BD y MinIO).

**Fixtures del backend:** con `SEGURPLUS_SEED_DOCUMENTS_SPEC_PATH` vacío (valor por defecto de `.env.example`), el arranque del backend con la semilla de dev solo siembra usuarios y no vuelve a crear fixtures encima de los documentos reales. Los tests del backend usan sus propios fixtures con Testcontainers.

## Tests
```bash
python -m pytest                   # 43 tests; requieren BD sembrada, MinIO y backend en :8100 (los marcados live)
python -m pytest -m "not live"     # sin backend ni MinIO (BD sembrada)
```
| Fichero | Qué comprueba |
|---|---|
| `test_dataset.py` | Anclas de octubre exactas leyendo la BD; 150-300 altas de sep-dic; ningún dato posterior a 2027-01-15 (salvo `renewal_date`) |
| `test_consistency.py` | Cifra del documento = cifra de BD: se extraen cifras de los PDF/XLSX/CSV/JSON generados (D01, D03, D06-D13, D15-D16, D18-D19, D21-D22, D24-D25, más D02 y contratos) y se comparan con SQL independiente; A5 (nóminas solo agregadas en D08/D09/D13) |
| `test_determinism.py` | Dos ejecuciones en directorios, procesos y `PYTHONHASHSEED` distintos dan el mismo SHA-256 |
| `test_imperfections.py` | Las imperfecciones declaradas existen (`IMPERFECCIONES.md`); los adversariales no están sembrados |
| `test_metadata.py` | `documents` = YAML; objetos de MinIO = checksums |
| `test_live_matrix.py` | 224 celdas usuario×documento (READ y DOWNLOAD por REST, instancia viva) contra el oráculo del YAML; recuento por KB 7/6/7/12; `monthly_salary` ausente |

Además, en el backend: `./mvnw verify` incluye `MonthlySalaryNotExposedTest` (OpenAPI y respuestas reales) y la regla ArchUnit `noSalaryInDtosOrEntities`.

## Determinismo (cómo se consigue)
- Dataset: `random.Random(SEED)`, listas propias de nombres (sin Faker), `uuid5` para todos los ids, orden explícito en cada consulta.
- PDF: reportlab con `invariant=1`, tipografías estándar (sin incrustar) y metadatos fijos.
- XLSX: propiedades fijas (`created/modified`), valores en caché calculados por un evaluador propio (SUM, SUMIF, ROUND…) e inyectados junto a las fórmulas reales, y zip reescrito con orden y fechas fijas.
- CSV/JSON: orden de filas y de claves explícitos, fin de línea `\n`.

Comprobación manual:
```bash
python -m segurplus_gen.seed --files-only --out /tmp/a && sleep 2 && (cd /tmp && python -m segurplus_gen.seed --files-only --out /tmp/b) ; diff <(cd /tmp/a && sha256sum *) <(cd /tmp/b && sha256sum *) && echo IDÉNTICOS
```
(ejecutar desde `generator/` con el venv activo).

## Modelo (resumen)
- 164 altas nuevas (sep 24, **oct 12**, nov 56, dic 72), 143 clientes, comisión de empleado 10 % (`ACCRUAL`; cancelación ⇒ `CLAWBACK` en el mes de cancelación), comisión de aseguradora por producto (auto 12 %, hogar 17 %, vida 20 %, salud 15 %), facturas emitidas a las aseguradoras (devengo en el mes de venta) y recibidas de proveedores, 48 gastos y objetivos por empleado y mes. Octubre: 12 pólizas, 9.680,00 €, 968,00 €, 7.250,00 €, objetivos 12.000,00 € y cumplimientos 105,0 / 76,0 / 73,2 / 68,8 % (80,7 %).
- **Reglas derivadas** (no están en la BD; parámetros de `facts.py`, declarados en los documentos): cotización del trabajador 6,48 %, IRPF por rol (22 % / 17 % / 17 % / 13 %), cotización de la empresa 31,60 %, aportación de socios 200.000 €, pago de nóminas el último día hábil, TGSS e IRPF el mes siguiente. Importes sin IVA (decisión del propietario).
- Salarios mensuales: DIR001 6.500, ACC001 3.800, COM001 3.900, EMP001 2.350, EMP002 2.300, EMP003 2.250, EMP004 2.300 (€).

## Formatos de los 32 documentos
PDF (20): D01, D04, D05, D06, D13, contratos D14/17/20/23, nóminas D16/19/22/25, D26-D29, G01-G03 · XLSX (6): D02, D07, D08, D10, D11, D12 · CSV (5): D09 (extracto bancario, `;` y coma decimal) y actividad comercial D15/18/21/24 (export CRM) · JSON (1): D03.

Estructura: `segurplus_gen/` (`dataset`, `db`, `facts`, `docs_*`, `texts_*`, `pdfkit`, `xlsxkit`, `build`, `storage`, `seed`), `tests/`, `adversarial/`, `IMPERFECCIONES.md`.
