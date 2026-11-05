package com.segurplus.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Ficheros de PRUEBA diminutos (no son el dataset real, que llega en F2): un PDF, un XLSX con fórmula, un CSV y un JSON. */
public final class FixtureFiles {

    private FixtureFiles() {
    }

    public static byte[] forType(FileType type, String code) {
        try {
            return switch (type) {
                case PDF -> pdf("Fixture " + code);
                case XLSX -> xlsx(code);
                case CSV -> ("code,value\n" + code + ",1\n").getBytes(StandardCharsets.UTF_8);
                case JSON -> ("{\"code\":\"" + code + "\",\"fixture\":true}").getBytes(StandardCharsets.UTF_8);
            };
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** XLSX con una hoja «Resumen»: A2=2, B2=3 y C2 = A2*B2 (la fórmula se evalúa en el servidor: 6). */
    public static byte[] xlsx(String code) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Resumen");
            sheet.createRow(0).createCell(0).setCellValue(code);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            row.createCell(2).setCellFormula("A2*B2");
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** PDF mínimo y válido de una página con una línea de texto (solo ASCII). */
    public static byte[] pdf(String text) {
        String safe = text.replaceAll("[^A-Za-z0-9 .,_-]", "?");
        String stream = "BT /F1 12 Tf 10 50 Td (" + safe + ") Tj ET";
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = sb.length();
            sb.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            sb.append(String.format("%010d 00000 n \n", offset));
        }
        sb.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
