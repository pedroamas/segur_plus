package com.segurplus.documents.api;

import java.util.List;

/**
 * Preview de un XLSX renderizado en el servidor: valores YA calculados (las fórmulas se evalúan en el servidor) como texto plano.
 * El cliente debe mostrarlos como texto, nunca como HTML. Los flags indican recortes por los límites configurados.
 */
public record ExcelPreview(List<Sheet> sheets, boolean sheetsTruncated) {

    public record Sheet(String name, List<List<String>> rows, boolean rowsTruncated, boolean columnsTruncated) {
    }
}
