"""Fixtures: los tests leen la BD de desarrollo ya sembrada con `python -m segurplus_gen.seed`."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from segurplus_gen import db  # noqa: E402
from segurplus_gen.facts import Facts  # noqa: E402


@pytest.fixture(scope="session")
def conn():
    c = db.connect()
    yield c
    c.close()


@pytest.fixture(scope="session")
def facts(conn):
    return Facts(conn)
