"""Formato es-ES independiente del locale del sistema (determinista)."""
from __future__ import annotations

from datetime import date
from decimal import Decimal


def num(x, dec: int = 2) -> str:
    q = Decimal(x).quantize(Decimal(1).scaleb(-dec))
    s = f"{abs(q):,.{dec}f}".replace(",", "§").replace(".", ",").replace("§", ".")
    return ("-" if q < 0 else "") + s


def eur(x) -> str:
    return num(x) + " €"


def pct(x, dec: int = 1) -> str:
    return num(x, dec) + " %"


def d(x: date) -> str:
    return x.strftime("%d/%m/%Y")


def signed_pct(new, old) -> str:
    if not old:
        return "n/d"
    v = (Decimal(new) - Decimal(old)) * 100 / Decimal(old)
    return ("+" if v > 0 else "") + num(v, 1) + " %"
