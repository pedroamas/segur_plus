"""Constantes del dataset y acceso a la configuración local (deploy/.env, nunca versionado)."""
from __future__ import annotations

import os
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parents[2]
GENERATOR = REPO / "generator"
SPEC_PATH = REPO / "docs" / "data-model" / "permissions-spec.yaml"
DEFAULT_OUT = GENERATOR / "out"

SEED = 20261015                      # semilla fija del dataset
BUSINESS_DATE = date(2027, 1, 15)    # «hoy» (ADR 0006); ningún dato posterior salvo renovaciones
ANCHOR_MONTH = date(2026, 10, 1)
MONTHS = [date(2026, 9, 1), date(2026, 10, 1), date(2026, 11, 1), date(2026, 12, 1)]
# Marca de tiempo fija para created_at/updated_at (reloj de negocio, no el real): el estado de la BD es reproducible.
SEED_TS = datetime(2027, 1, 15, 9, 0, 0, tzinfo=timezone.utc)

BUCKET = "segurplus-documents"       # igual que segurplus.storage.bucket del backend
KEY_PREFIX = "documents/"            # igual que DocumentService.store: documents/<uuid>.<ext>

# Sueldos mensuales brutos (ficticios) de los 7 usuarios; solo para generar nóminas y presupuesto.
SALARIES = {
    "DIR001": "6500.00", "ACC001": "3800.00", "COM001": "3900.00",
    "EMP001": "2350.00", "EMP002": "2300.00", "EMP003": "2250.00", "EMP004": "2300.00",
}


def load_env() -> dict[str, str]:
    """Lee deploy/.env sin sobrescribir variables ya presentes en el entorno. No imprime nada."""
    env_file = REPO / "deploy" / ".env"
    values: dict[str, str] = {}
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"').strip("'")
    values.update({k: v for k, v in os.environ.items() if k in values or k.startswith(("POSTGRES_", "MINIO_", "SEGURPLUS_", "DB_"))})
    return values


def db_conninfo(env: dict[str, str] | None = None) -> dict:
    env = env or load_env()
    host, port, dbname = "127.0.0.1", 5434, env.get("POSTGRES_DB", "segurplus")
    if env.get("DB_URL", "").startswith("jdbc:"):
        u = urlparse(env["DB_URL"][len("jdbc:"):])
        host, port = u.hostname or host, u.port or port
        dbname = (u.path or "/" + dbname).lstrip("/") or dbname
    return {"host": host, "port": port, "dbname": dbname, "user": env["POSTGRES_USER"], "password": env["POSTGRES_PASSWORD"]}


def minio_settings(env: dict[str, str] | None = None) -> dict:
    env = env or load_env()
    u = urlparse(env.get("MINIO_ENDPOINT", "http://localhost:9000"))
    return {"endpoint": f"{u.hostname}:{u.port or 9000}", "access_key": env["MINIO_ROOT_USER"],
            "secret_key": env["MINIO_ROOT_PASSWORD"], "secure": u.scheme == "https"}
