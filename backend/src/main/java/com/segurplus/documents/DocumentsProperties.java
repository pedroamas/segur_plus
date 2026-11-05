package com.segurplus.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("segurplus.documents")
record DocumentsProperties(long maxUploadBytes, Preview preview) {

    DocumentsProperties {
        maxUploadBytes = maxUploadBytes <= 0 ? 10L * 1024 * 1024 : maxUploadBytes;
        preview = preview == null ? new Preview(5, 200, 30, 500) : preview;
    }

    /** Límites del preview Excel: hojas, filas y columnas por hoja, y longitud de texto por celda. */
    record Preview(int maxSheets, int maxRows, int maxColumns, int maxCellChars) {
    }
}
