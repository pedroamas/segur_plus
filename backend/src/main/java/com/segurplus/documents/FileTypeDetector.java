package com.segurplus.documents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.shared.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Determina el tipo REAL por el contenido (firma/estructura), no por el Content-Type ni la extensión que declara el
 * cliente, y exige que la extensión coincida. Tipos admitidos: PDF, XLSX, CSV y JSON.
 */
final class FileTypeDetector {

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        // Defensa contra zip bombs al abrir XLSX (valores por defecto de POI más estrictos en tamaño de entrada).
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
    }

    private FileTypeDetector() {
    }

    static FileType detectAndMatch(byte[] content, String originalFilename) {
        FileType declared = fromExtension(originalFilename).orElseThrow(() -> ApiException
                .unsupportedMedia("Unsupported file extension. Allowed: .pdf, .xlsx, .csv, .json."));
        FileType actual = detect(content).orElseThrow(() -> ApiException
                .unsupportedMedia("The file content is not a valid PDF, XLSX, CSV or JSON document."));
        if (actual != declared) {
            throw ApiException.unsupportedMedia("The file extension does not match the actual file content.");
        }
        return actual;
    }

    static Optional<FileType> fromExtension(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return Optional.empty();
        }
        return switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "pdf" -> Optional.of(FileType.PDF);
            case "xlsx" -> Optional.of(FileType.XLSX);
            case "csv" -> Optional.of(FileType.CSV);
            case "json" -> Optional.of(FileType.JSON);
            default -> Optional.empty();
        };
    }

    static Optional<FileType> detect(byte[] b) {
        if (b == null || b.length == 0) {
            return Optional.empty();
        }
        if (startsWith(b, "%PDF-")) {
            return endsWithEof(b) ? Optional.of(FileType.PDF) : Optional.empty();
        }
        if (b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            return isWorkbook(b) ? Optional.of(FileType.XLSX) : Optional.empty();
        }
        Optional<String> text = decodeUtf8(b);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String s = text.get();
        char first = firstNonSpace(s);
        if (first == '{' || first == '[') {
            return isJson(b) ? Optional.of(FileType.JSON) : Optional.empty();
        }
        return isPlainText(s) ? Optional.of(FileType.CSV) : Optional.empty();
    }

    private static boolean startsWith(byte[] b, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (b.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (b[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWithEof(byte[] b) {
        int from = Math.max(0, b.length - 1024);
        return new String(b, from, b.length - from, StandardCharsets.ISO_8859_1).contains("%%EOF");
    }

    private static boolean isWorkbook(byte[] b) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(b))) {
            return wb.getNumberOfSheets() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Optional<String> decodeUtf8(byte[] b) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static char firstNonSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '﻿') {
                return s.charAt(i);
            }
        }
        return 0;
    }

    private static boolean isPlainText(String s) {
        if (s.isBlank()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    private static boolean isJson(byte[] b) {
        try (JsonParser p = JSON.getFactory().createParser(b)) {
            p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonToken first = p.nextToken();
            if (first != JsonToken.START_OBJECT && first != JsonToken.START_ARRAY) {
                return false;
            }
            p.skipChildren();
            return p.nextToken() == null;
        } catch (IOException e) {
            return false;
        }
    }
}
