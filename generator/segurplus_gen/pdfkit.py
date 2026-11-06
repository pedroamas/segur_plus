"""Informes PDF con aspecto profesional (reportlab). `invariant=1` + metadatos fijos => PDF byte a byte reproducible."""
from __future__ import annotations

from reportlab.graphics.charts.barcharts import VerticalBarChart
from reportlab.graphics.charts.legends import Legend
from reportlab.graphics.shapes import Drawing, String
from reportlab.lib import colors
from reportlab.lib.enums import TA_JUSTIFY, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas as rl_canvas
from reportlab.platypus import (KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle)

NAVY = colors.HexColor("#12355B")
TEAL = colors.HexColor("#1B998B")
GREY = colors.HexColor("#5B6770")
LIGHT = colors.HexColor("#EEF2F6")
RED = colors.HexColor("#B3261E")
GREEN = colors.HexColor("#1E7B4F")
PALETTE = [NAVY, TEAL, colors.HexColor("#E0A526"), colors.HexColor("#8E6BBF"), GREY]

_base = getSampleStyleSheet()
BODY = ParagraphStyle("body", parent=_base["BodyText"], fontName="Helvetica", fontSize=9.5, leading=13.5, alignment=TA_JUSTIFY, spaceAfter=5)
H1 = ParagraphStyle("h1", parent=_base["Heading1"], fontName="Helvetica-Bold", fontSize=15, textColor=NAVY, spaceBefore=10, spaceAfter=6, keepWithNext=1)
H2 = ParagraphStyle("h2", parent=_base["Heading2"], fontName="Helvetica-Bold", fontSize=11.5, textColor=TEAL, spaceBefore=8, spaceAfter=4, keepWithNext=1)
SMALL = ParagraphStyle("small", parent=BODY, fontSize=8, leading=10.5, textColor=GREY, alignment=0)
CELL = ParagraphStyle("cell", parent=BODY, fontSize=8.5, leading=11, alignment=0, spaceAfter=0)
CELL_R = ParagraphStyle("cellr", parent=CELL, alignment=TA_RIGHT)
CELL_H = ParagraphStyle("cellh", parent=CELL, fontName="Helvetica-Bold", textColor=colors.white)
CELL_HR = ParagraphStyle("cellhr", parent=CELL_H, alignment=TA_RIGHT)
CELL_B = ParagraphStyle("cellb", parent=CELL, fontName="Helvetica-Bold")
CELL_BR = ParagraphStyle("cellbr", parent=CELL_B, alignment=TA_RIGHT)


def esc(t: str) -> str:
    return str(t).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class _Numbered(rl_canvas.Canvas):
    """Canvas en dos pasadas para «Página x de y»; hereda `invariant` del documento."""
    meta: dict = {}

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._saved = []

    def showPage(self):
        self._saved.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        total = len(self._saved)
        for state in self._saved:
            self.__dict__.update(state)
            self._footer(total)
            super().showPage()
        super().save()

    def _footer(self, total):
        m = self.meta
        w, h = A4
        self.setFillColor(NAVY)
        self.rect(0, h - 16 * mm, w, 16 * mm, stroke=0, fill=1)
        self.setFillColor(colors.white)
        self.setFont("Helvetica-Bold", 12)
        self.drawString(18 * mm, h - 10.5 * mm, "SegurPlus")
        self.setFont("Helvetica", 8.5)
        self.drawRightString(w - 18 * mm, h - 10.5 * mm, m.get("header_right", ""))
        self.setStrokeColor(colors.HexColor("#B8C4D0"))
        self.line(18 * mm, 15 * mm, w - 18 * mm, 15 * mm)
        self.setFillColor(GREY)
        self.setFont("Helvetica", 7.5)
        self.drawString(18 * mm, 10.5 * mm, m.get("footer_left", ""))
        self.drawRightString(w - 18 * mm, 10.5 * mm, f"Página {self._pageNumber} de {total}")


class Report:
    def __init__(self, title: str, subtitle: str = "", *, code: str = "", classification: str = "", version: str = "",
                 date_str: str = "", author: str = "SegurPlus", header_right: str | None = None):
        self.title, self.subtitle, self.author = title, subtitle, author
        self.meta = {"header_right": header_right if header_right is not None else (f"{code} · {classification}" if code else classification),
                     "footer_left": f"{title}{(' · ' + version) if version else ''}{(' · ' + date_str) if date_str else ''} · Documento ficticio de SegurPlus"}
        self.story: list = []
        self._title_block(code, classification, version, date_str)

    def _title_block(self, code, classification, version, date_str):
        self.story.append(Spacer(1, 4 * mm))
        self.story.append(Paragraph(esc(self.title), ParagraphStyle("t", parent=H1, fontSize=20, leading=24, spaceBefore=0)))
        if self.subtitle:
            self.story.append(Paragraph(esc(self.subtitle), ParagraphStyle("st", parent=BODY, fontSize=11, textColor=GREY, alignment=0)))
        bits = [b for b in (f"Código {code}" if code else "", f"Clasificación: {classification}" if classification else "",
                            f"Versión {version}" if version else "", date_str) if b]
        if bits:
            self.story.append(Paragraph(esc("  ·  ".join(bits)), SMALL))
        self.story.append(Spacer(1, 3 * mm))

    # --- bloques ---------------------------------------------------------------------------------------------------
    def h1(self, t):
        self.story.append(Paragraph(esc(t), H1))

    def h2(self, t):
        self.story.append(Paragraph(esc(t), H2))

    def p(self, t, style=BODY):
        self.story.append(Paragraph(esc(t) if "<" not in t else t, style))

    def raw(self, t, style=BODY):
        self.story.append(Paragraph(t, style))

    def small(self, t):
        self.story.append(Paragraph(esc(t), SMALL))

    def bullets(self, items):
        for it in items:
            self.story.append(Paragraph("•&nbsp;&nbsp;" + esc(it), ParagraphStyle("b", parent=BODY, leftIndent=12, firstLineIndent=-9, spaceAfter=2)))
        self.story.append(Spacer(1, 2 * mm))

    def spacer(self, mm_=4):
        self.story.append(Spacer(1, mm_ * mm))

    def pagebreak(self):
        self.story.append(PageBreak())

    def table(self, head, rows, widths=None, *, total=False, align_right_from=1, zebra=True, keep=True):
        """rows: listas de str. Columnas desde `align_right_from` alineadas a la derecha. `total`: última fila en negrita."""
        data = [[Paragraph(esc(h), CELL_HR if i >= align_right_from else CELL_H) for i, h in enumerate(head)]]
        for ri, r in enumerate(rows):
            bold = total and ri == len(rows) - 1
            data.append([Paragraph(esc(c), (CELL_BR if bold else CELL_R) if ci >= align_right_from else (CELL_B if bold else CELL)) for ci, c in enumerate(r)])
        avail = A4[0] - 36 * mm
        cw = [avail * w / sum(widths) for w in widths] if widths else None
        t = Table(data, colWidths=cw, repeatRows=1)
        st = [("BACKGROUND", (0, 0), (-1, 0), NAVY), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
              ("LINEBELOW", (0, 0), (-1, -1), 0.25, colors.HexColor("#D5DDE5")), ("TOPPADDING", (0, 0), (-1, -1), 3),
              ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
        if zebra:
            st += [("BACKGROUND", (0, i), (-1, i), LIGHT) for i in range(2, len(data), 2)]
        if total and rows:
            st += [("BACKGROUND", (0, len(data) - 1), (-1, len(data) - 1), colors.HexColor("#DCE6F0")), ("LINEABOVE", (0, len(data) - 1), (-1, len(data) - 1), 0.8, NAVY)]
        t.setStyle(TableStyle(st))
        self.story.append(KeepTogether([t, Spacer(1, 3 * mm)]) if keep and len(rows) < 25 else t)
        if not (keep and len(rows) < 25):
            self.story.append(Spacer(1, 3 * mm))

    def kpis(self, items):
        """items: (etiqueta, valor, variación|None, bueno: bool|None)."""
        cells = []
        for label, value, delta, good in items:
            col = GREEN if good is True else RED if good is False else GREY
            parts = [Paragraph(esc(label), ParagraphStyle("kl", parent=SMALL, fontSize=7.5)),
                     Paragraph(esc(value), ParagraphStyle("kv", parent=BODY, fontName="Helvetica-Bold", fontSize=14, textColor=NAVY, alignment=0, leading=17, spaceAfter=0))]
            if delta:
                parts.append(Paragraph(esc(delta), ParagraphStyle("kd", parent=SMALL, fontSize=7.5, textColor=col)))
            cells.append(parts)
        n = len(cells)
        w = (A4[0] - 36 * mm) / n
        t = Table([cells], colWidths=[w] * n)
        t.setStyle(TableStyle([("BOX", (0, 0), (-1, -1), 0.5, colors.HexColor("#B8C4D0")), ("INNERGRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#B8C4D0")),
                               ("BACKGROUND", (0, 0), (-1, -1), LIGHT), ("VALIGN", (0, 0), (-1, -1), "TOP"),
                               ("LEFTPADDING", (0, 0), (-1, -1), 6), ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 5)]))
        self.story.append(t)
        self.story.append(Spacer(1, 4 * mm))

    def bar_chart(self, categories, series: dict[str, list[float]], title="", height=55 * mm):
        w = A4[0] - 36 * mm
        d = Drawing(w, height)
        d.add(String(0, height - 8, title, fontName="Helvetica-Bold", fontSize=9, fillColor=NAVY))
        ch = VerticalBarChart()
        ch.x, ch.y, ch.width, ch.height = 40, 22, w - 60, height - 42
        ch.data = [[float(x) for x in v] for v in series.values()]
        ch.categoryAxis.categoryNames = list(categories)
        ch.categoryAxis.labels.fontName = ch.valueAxis.labels.fontName = "Helvetica"
        ch.categoryAxis.labels.fontSize = ch.valueAxis.labels.fontSize = 7.5
        ch.valueAxis.valueMin = 0
        ch.valueAxis.gridStrokeColor = colors.HexColor("#D5DDE5")
        ch.valueAxis.visibleGrid = True
        ch.barWidth = 8
        ch.groupSpacing = 8
        for i in range(len(series)):
            ch.bars[i].fillColor = PALETTE[i % len(PALETTE)]
            ch.bars[i].strokeColor = None
        d.add(ch)
        if len(series) > 1:
            lg = Legend()
            lg.x, lg.y = w - 160, height - 6
            lg.alignment = "right"
            lg.fontName, lg.fontSize = "Helvetica", 7.5
            lg.colorNamePairs = [(PALETTE[i % len(PALETTE)], n) for i, n in enumerate(series)]
            d.add(lg)
        self.story.append(d)
        self.story.append(Spacer(1, 3 * mm))

    def build(self, path) -> None:
        _Numbered.meta = self.meta
        doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=24 * mm, bottomMargin=22 * mm,
                                title=self.title, author=self.author, subject=self.subtitle or self.title, creator="SegurPlus generator",
                                invariant=1)
        doc.build(self.story, canvasmaker=_Numbered)
