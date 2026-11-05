package com.segurplus.documents;

import com.segurplus.documents.api.ExcelPreview;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Render de XLSX en el servidor con Apache POI. Valores calculados (FormulaEvaluator, sin referencias externas), solo
 * hojas/filas/columnas visibles, límites de hojas, filas, columnas y longitud de celda, y texto saneado (sin caracteres de
 * control). El resultado es texto plano: ningún HTML, ninguna fórmula ni enlace del fichero original llega al cliente.
 */
final class ExcelPreviewRenderer {

    private ExcelPreviewRenderer() {
    }

    static ExcelPreview render(byte[] xlsx, DocumentsProperties.Preview limits) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            evaluator.setIgnoreMissingWorkbooks(true);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<ExcelPreview.Sheet> sheets = new ArrayList<>();
            boolean sheetsTruncated = false;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetVisibility(i) != SheetVisibility.VISIBLE) {
                    continue;
                }
                if (sheets.size() >= limits.maxSheets()) {
                    sheetsTruncated = true;
                    break;
                }
                sheets.add(renderSheet(wb.getSheetAt(i), evaluator, formatter, limits));
            }
            return new ExcelPreview(sheets, sheetsTruncated);
        }
    }

    private static ExcelPreview.Sheet renderSheet(Sheet sheet, FormulaEvaluator evaluator, DataFormatter formatter,
            DocumentsProperties.Preview limits) {
        List<List<String>> rows = new ArrayList<>();
        boolean rowsTruncated = false;
        boolean columnsTruncated = false;
        Iterator<Row> it = sheet.rowIterator();
        while (it.hasNext()) {
            Row row = it.next();
            if (row.getZeroHeight()) {
                continue;
            }
            if (rows.size() >= limits.maxRows()) {
                rowsTruncated = true;
                break;
            }
            int lastCell = Math.max(row.getLastCellNum(), 0);
            if (lastCell > limits.maxColumns()) {
                columnsTruncated = true;
            }
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < Math.min(lastCell, limits.maxColumns()); c++) {
                cells.add(sheet.isColumnHidden(c) ? "" : text(row.getCell(c), evaluator, formatter, limits.maxCellChars()));
            }
            rows.add(cells);
        }
        return new ExcelPreview.Sheet(sanitize(sheet.getSheetName(), 100), rows, rowsTruncated, columnsTruncated);
    }

    private static String text(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter, int maxChars) {
        if (cell == null) {
            return "";
        }
        String value;
        try {
            value = formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            value = "#ERROR";
        }
        return sanitize(value, maxChars);
    }

    /** Sustituye caracteres de control por espacio (salvo salto de línea y tabulador) y recorta. */
    static String sanitize(String value, int maxChars) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), maxChars));
        for (int i = 0; i < value.length() && sb.length() < maxChars; i++) {
            char ch = value.charAt(i);
            boolean control = Character.isISOControl(ch) && ch != '\n' && ch != '\t';
            sb.append(control || ch == ' ' || ch == ' ' ? ' ' : ch);
        }
        return sb.toString();
    }
}
