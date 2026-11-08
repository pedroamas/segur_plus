package com.segurplus.ai;

import java.util.List;
import java.util.Map;

/** Salida de una herramienta: {@code {tool, params, asOf, currency, rows[], totals}} (ADR 0005 §5). */
record ToolResult(String tool, Map<String, String> params, String asOf, String currency, List<Map<String, Object>> rows,
        Map<String, Object> totals, String note) {
}
