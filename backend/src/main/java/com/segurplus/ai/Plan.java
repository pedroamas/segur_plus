package com.segurplus.ai;

import java.util.List;

/** Plan ya validado por el backend: herramientas del catálogo del rol y, si procede, la consulta documental. */
record Plan(List<ToolCall> tools, String retrieveQuery, boolean fallback) {
}
