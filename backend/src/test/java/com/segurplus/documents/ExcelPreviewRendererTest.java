package com.segurplus.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.segurplus.documents.api.ExcelPreview;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Límites y saneado del preview Excel (sin Spring). */
class ExcelPreviewRendererTest {

    private static final DocumentsProperties.Preview LIMITS = new DocumentsProperties.Preview(3, 10, 4, 20);

    private static byte[] workbook(XSSFWorkbook wb) throws Exception {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void limitsSheetsRowsColumnsAndCellLength() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        for (int s = 0; s < 5; s++) {
            Sheet sheet = wb.createSheet("S" + s);
            for (int r = 0; r < 50; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < 9; c++) {
                    row.createCell(c).setCellValue("x".repeat(100));
                }
            }
        }
        ExcelPreview p = ExcelPreviewRenderer.render(workbook(wb), LIMITS);
        assertThat(p.sheets()).hasSize(3);
        assertThat(p.sheetsTruncated()).isTrue();
        ExcelPreview.Sheet first = p.sheets().get(0);
        assertThat(first.rows()).hasSize(10);
        assertThat(first.rowsTruncated()).isTrue();
        assertThat(first.columnsTruncated()).isTrue();
        assertThat(first.rows().get(0)).hasSize(4).allMatch(v -> v.length() == 20);
    }

    @Test
    void evaluatesFormulasAndReportsErrorsAsText() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Row row = wb.createSheet("F").createRow(0);
        row.createCell(0).setCellValue(10);
        row.createCell(1).setCellFormula("A1*2");
        row.createCell(2).setCellFormula("1/0");
        row.createCell(3).setCellFormula("SUM(A1:B1)");
        ExcelPreview p = ExcelPreviewRenderer.render(workbook(wb), new DocumentsProperties.Preview(5, 10, 10, 50));
        assertThat(p.sheets().get(0).rows().get(0)).containsExactly("10", "20", "#DIV/0!", "30");
    }

    @Test
    void sanitizesControlCharactersAndKeepsMarkupAsInertText() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Row row = wb.createSheet("<img src=x onerror=alert(1)>").createRow(0);
        row.createCell(0).setCellValue("<script>alert(1)</script>\u0007\u0000\u001b[31m");
        row.createCell(1).setCellValue("=HYPERLINK(\"http://evil\",\"x\")");
        ExcelPreview p = ExcelPreviewRenderer.render(workbook(wb), new DocumentsProperties.Preview(5, 10, 10, 200));
        String cell = p.sheets().get(0).rows().get(0).get(0);
        assertThat(cell).startsWith("<script>").doesNotContain("\u0007").doesNotContain("\u0000").doesNotContain("\u001b");
        // El texto «=HYPERLINK» es un literal: no es una fórmula del libro y no se evalúa.
        assertThat(p.sheets().get(0).rows().get(0).get(1)).isEqualTo("=HYPERLINK(\"http://evil\",\"x\")");
    }

    @Test
    void hiddenSheetsRowsAndColumnsAreNotRendered() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet visible = wb.createSheet("visible");
        visible.createRow(0).createCell(0).setCellValue("public");
        visible.getRow(0).createCell(1).setCellValue("hiddencol");
        visible.setColumnHidden(1, true);
        Row hiddenRow = visible.createRow(1);
        hiddenRow.createCell(0).setCellValue("hiddenrow");
        hiddenRow.setZeroHeight(true);
        Sheet secret = wb.createSheet("secret");
        secret.createRow(0).createCell(0).setCellValue("top-secret");
        wb.setSheetHidden(1, true);
        ExcelPreview p = ExcelPreviewRenderer.render(workbook(wb), LIMITS);
        assertThat(p.sheets()).hasSize(1);
        assertThat(p.sheets().get(0).rows()).hasSize(1);
        assertThat(p.sheets().toString()).doesNotContain("top-secret").doesNotContain("hiddenrow").doesNotContain("hiddencol");
    }
}
