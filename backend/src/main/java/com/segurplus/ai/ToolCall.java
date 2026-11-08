package com.segurplus.ai;

import java.util.Map;

/** Llamada ya validada contra el catálogo: solo nombres y parámetros permitidos (ADR 0005); nunca identidad. */
record ToolCall(String name, Map<String, String> params) {
}
