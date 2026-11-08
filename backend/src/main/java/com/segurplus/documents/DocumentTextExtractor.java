package com.segurplus.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Texto plano de un documento para indexarlo (PDF, XLSX, CSV, JSON). Cada página/hoja lleva una cabecera con código y
 * título para que cada fragmento recuperado conserve su contexto. Se usa tanto la semilla como las subidas del MANAGER.
 */
final class DocumentTextExtractor {

    private static final int MAX_CHARS = 2_000_000;

    private DocumentTextExtractor() {
    }

    static String extract(FileType type, byte[] content, String code, String title) throws IOException {
        String header = "Documento " + code + " — " + title;
        String body = switch (type) {
            case PDF -> pdf(content, header);
            case XLSX -> xlsx(content, header);
            case CSV, JSON -> header + "\n\n" + new String(content, StandardCharsets.UTF_8);
        };
        return body.length() > MAX_CHARS ? body.substring(0, MAX_CHARS) : body;
    }

    private static String pdf(byte[] content, String header) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).strip();
                if (!text.isEmpty()) {
                    sb.append(header).append(" (página ").append(page).append(")\n").append(text).append("\n\n");
                }
            }
            return sb.toString();
        }
    }

    private static String xlsx(byte[] content, String header) throws IOException {
        DataFormatter formatter = new DataFormatter(java.util.Locale.forLanguageTag("es-ES"));
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : wb) {
                sb.append(header).append(" — hoja ").append(sheet.getSheetName()).append("\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String v = formatter.formatCellValue(cell, evaluator).strip();
                        if (!v.isEmpty()) {
                            if (!line.isEmpty()) {
                                line.append(" | ");
                            }
                            line.append(v);
                        }
                    }
                    if (!line.isEmpty()) {
                        sb.append(line).append("\n");
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
