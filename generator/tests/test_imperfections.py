"""Imperfecciones deliberadas (generator/IMPERFECCIONES.md): existen, están dentro del modelo de permisos y no alteran las anclas."""
from decimal import Decimal

from segurplus_gen.build import build_all
from segurplus_gen.spec import doc_spec
from tests.helpers import after, pdf_lines, q2, sheet_rows, find_row, parse_num, value
import pytest


@pytest.fixture(scope="module")
def files(facts, tmp_path_factory):
    return {g.code: g.path for g in build_all(facts, tmp_path_factory.mktemp("imp"))}


def test_rounding_difference_between_d08_and_d13(files):
    d08 = sheet_rows(files["D08"], "Resultados")
    ss_d08 = -q2(find_row(d08, "642")[2])
    ss_d13 = -value(pdf_lines(files["D13"]), "Seguridad Social a cargo de la empresa")
    assert ss_d08 != ss_d13 and abs(ss_d08 - ss_d13) == Decimal("0.01")


def test_two_versions_of_the_october_report_are_distinct_documents(files):
    v1, v2 = " ".join(pdf_lines(files["D13"])), " ".join(pdf_lines(files["D01"]))
    assert "Versión 1.0 (borrador)" in v1 and "Versión 2.0 (final)" in v2
    assert doc_spec("D13").kb == "FINANZAS" and doc_spec("D01").kb == "DIRECCION"      # la v1 la ve Finanzas; la v2, solo Dirección


def test_near_identical_pair_has_different_classification(files):
    import json
    assert doc_spec("D02").cls == "RESTRICTED" and doc_spec("D03").cls == "CONFIDENTIAL"
    d03 = json.loads(files["D03"].read_text(encoding="utf-8"))
    assert "presupuesto_2026" in d03                      # D03 repite las líneas de D02 sin sueldos individuales


def test_unreconciled_items_in_bank_reconciliation(files):
    rows = [r for r in sheet_rows(files["D10"], "Partidas") if r[0] in ("Libros", "Banco")]
    assert sorted(r[5] for r in rows) == ["En tránsito", "Pendiente de contabilizar", "Sin identificar"]


def test_adversarial_documents_are_not_seeded(conn):
    from pathlib import Path
    adv = Path(__file__).resolve().parents[1] / "adversarial"
    assert len([p for p in adv.iterdir() if p.name.startswith("ADV-")]) >= 2
    assert conn.execute("SELECT count(*) FROM documents WHERE code LIKE 'ADV%%'").fetchone()[0] == 0
    assert conn.execute("SELECT count(*) FROM documents").fetchone()[0] == 32
