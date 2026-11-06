"""Títulos y formato de cada documento. Código, KB, clasificación, categoría, propietario y roles salen SIEMPRE de
permissions-spec.yaml (spec.py); aquí solo lo que el YAML no declara: título visible y tipo de fichero."""
from __future__ import annotations

EXT = {"PDF": "pdf", "XLSX": "xlsx", "CSV": "csv", "JSON": "json"}
CORPORATE = {
    "D01": ("Informe mensual de dirección", "PDF"),
    "D02": ("Presupuesto anual 2026 (incluye salarios)", "XLSX"),
    "D03": ("Cuadro de mando KPI", "JSON"),
    "D04": ("Plan estratégico 2026-2028", "PDF"),
    "D05": ("Actas comité de dirección", "PDF"),
    "D06": ("Rendimiento comercial (CRM)", "PDF"),
    "D07": ("Facturas emitidas y recibidas", "XLSX"),
    "D08": ("Libro mayor (nóminas agregadas)", "XLSX"),
    "D09": ("Extracto bancario (nóminas agregadas)", "CSV"),
    "D10": ("Conciliación bancaria", "XLSX"),
    "D11": ("Liquidación de comisiones (todos los empleados)", "XLSX"),
    "D12": ("Registro de gastos", "XLSX"),
    "D13": ("Informe de cierre contable", "PDF"),
    "D26": ("Manual de seguros de auto", "PDF"),
    "D27": ("Manual de seguros de hogar", "PDF"),
    "D28": ("Manual de seguros de vida", "PDF"),
    "D29": ("Manual de seguros de salud", "PDF"),
    "G01": ("Código de conducta", "PDF"),
    "G02": ("Política de seguridad de la información", "PDF"),
    "G03": ("Procedimiento de gastos y vacaciones", "PDF"),
}
# Documentos por empleado: código -> (empleado, tipo)
PER_EMPLOYEE = {}
for _i, _emp in enumerate(["EMP001", "EMP002", "EMP003", "EMP004"]):
    PER_EMPLOYEE[f"D{14 + 3 * _i}"] = (_emp, "contract")
    PER_EMPLOYEE[f"D{15 + 3 * _i}"] = (_emp, "activity")
    PER_EMPLOYEE[f"D{16 + 3 * _i}"] = (_emp, "payroll")


def title_and_type(code: str, employee_name: dict[str, str] | None = None) -> tuple[str, str]:
    if code in CORPORATE:
        return CORPORATE[code]
    emp, kind = PER_EMPLOYEE[code]
    name = f"{employee_name[emp]} ({emp})" if employee_name else emp
    return {"contract": (f"Contrato de trabajo de {name}", "PDF"),
            "activity": (f"Actividad comercial de {name}", "CSV"),
            "payroll": (f"Nómina y comisiones de {name}", "PDF")}[kind]
