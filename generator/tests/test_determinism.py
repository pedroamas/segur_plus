"""Criterio F2 2: dos ejecuciones con la misma semilla producen ficheros con el MISMO SHA-256 (en directorios y procesos distintos)."""
import hashlib
import os
import subprocess
import sys
import time
from pathlib import Path

GEN = Path(__file__).resolve().parents[1]


def run(out: Path, hashseed: str, cwd: Path) -> dict[str, str]:
    env = {**os.environ, "PYTHONHASHSEED": hashseed}
    subprocess.run([sys.executable, "-m", "segurplus_gen.seed", "--files-only", "--out", str(out)], cwd=GEN, env=env, check=True, capture_output=True)
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir())}


def test_two_runs_in_different_directories_give_identical_sha256(tmp_path, conn):
    a = run(tmp_path / "ejecucion_a", "0", tmp_path)
    time.sleep(1.2)                                    # cruza un cambio de segundo: detectaría marcas de tiempo
    b = run(tmp_path / "otro" / "ejecucion_b", "12345", tmp_path)
    assert len(a) == 33 and a == b                     # 32 documentos + manifest.json
    seeded = dict(conn.execute("SELECT code, checksum_sha256 FROM documents").fetchall())
    for name, digest in a.items():
        if name != "manifest.json":
            assert seeded[name.split(".")[0]] == digest
