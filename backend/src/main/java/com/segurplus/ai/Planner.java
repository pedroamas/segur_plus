package com.segurplus.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.auth.Subject;
import com.segurplus.auth.CurrentUserService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Llamada de planificación (ADR 0003 §6, ADR 0005): un modelo SIN conocimiento propone, en JSON, qué herramientas usar y si
 * buscar en documentos. El backend NO se fía: valida contra el catálogo del rol, descarta parámetros no permitidos
 * ({@code employee_id}, {@code role}, {@code datasetId}…) y, si el JSON no sirve, cae a «buscar en documentos con la pregunta».
 * El planificador solo ve la pregunta y el historial del usuario; nunca fragmentos ni resultados.
 */
@Component
class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);
    private static final int MAX_TOOL_CALLS = 4;

    private final AiGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CurrentUserService users;

    Planner(AiGateway gateway, ObjectMapper mapper, Clock clock, CurrentUserService users) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.clock = clock;
        this.users = users;
    }

    record Planned(Plan plan, AiGateway.Usage usage, int droppedParams) {
    }

    Planned plan(Subject subject, AiProperties.Profile profile, String message, String history) {
        String prompt = planningPrompt(profile, message, history);
        try {
            AiGateway.Completion c = gateway.plan(prompt, users.difyUserRef(subject));
            return parse(c.text(), profile, message, c.usage());
        } catch (AiException e) {
            log.warn("Planning unavailable ({}); using document-search fallback", e.kind());
            return new Planned(new Plan(List.of(), message, true), AiGateway.Usage.NONE, 0);
        }
    }

    String planningPrompt(AiProperties.Profile profile, String message, String history) {
        StringBuilder tools = new StringBuilder();
        for (String t : profile.tools()) {
            if (ToolExecutor.DATA_TOOLS.contains(t)) {
                tools.append("- ").append(t).append('\n');
            }
        }
        return """
                FECHA DE NEGOCIO (hoy): %s
                HERRAMIENTAS DISPONIBLES (solo estas):
                %s- search_documents  (búsqueda en documentos; parámetro: query)
                Parámetros de las herramientas de datos: period (MONTH:YYYY-MM | THIS_MONTH | LAST_MONTH | YEAR_TO_DATE) y, solo en get_policies_by_status, status (ACTIVE | CANCELLED | EXPIRED).
                No existen parámetros de empleado, rol, usuario ni dataset.
                HISTORIAL RECIENTE:
                %s
                PREGUNTA DEL USUARIO:
                %s
                """.formatted(LocalDate.now(clock), tools, history.isBlank() ? "(vacío)" : history, message);
    }

    Planned parse(String raw, AiProperties.Profile profile, String message, AiGateway.Usage usage) {
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(raw));
        } catch (Exception e) {
            return new Planned(new Plan(List.of(), message, true), usage, 0);
        }
        List<ToolCall> calls = new ArrayList<>();
        int dropped = 0;
        String query = null;
        boolean wantsDocs = false;
        JsonNode tools = root.path("tools");
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                String name = t.path("name").asText("");
                if ("search_documents".equals(name) && profile.tools().contains(name)) {
                    wantsDocs = true;
                    String q = t.path("params").path("query").asText("");
                    if (!q.isBlank()) {
                        query = q;
                    }
                    continue;
                }
                if (!ToolExecutor.DATA_TOOLS.contains(name) || !profile.tools().contains(name) || calls.size() >= MAX_TOOL_CALLS) {
                    dropped++;
                    continue;
                }
                Map<String, String> params = new LinkedHashMap<>();
                JsonNode p = t.path("params");
                var it = p.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    if (!ToolExecutor.ALLOWED_PARAMS.contains(e.getKey()) || !e.getValue().isValueNode()) {
                        dropped++; // p. ej. employee_id: se descarta, nunca se usa
                        continue;
                    }
                    String v = e.getValue().asText();
                    if (e.getKey().equals("status") && !ToolExecutor.STATUSES.contains(v.toUpperCase())) {
                        dropped++;
                        continue;
                    }
                    params.put(e.getKey(), e.getKey().equals("status") ? v.toUpperCase() : v);
                }
                if (!name.equals("get_policies_by_status")) {
                    params.remove("status");
                }
                calls.add(new ToolCall(name, params));
            }
        }
        JsonNode retrieve = root.path("retrieve");
        if (retrieve.isObject()) {
            wantsDocs = true;
            String q = retrieve.path("query").asText("");
            if (!q.isBlank()) {
                query = q;
            }
        } else if (retrieve.isBoolean() && retrieve.asBoolean()) {
            wantsDocs = true;
        } else if (retrieve.isTextual() && !retrieve.asText().isBlank()) {
            wantsDocs = true;
            query = retrieve.asText();
        }
        if (calls.isEmpty() && !wantsDocs) {
            wantsDocs = true; // sin herramienta ni búsqueda propuestas: se busca en documentos
        }
        String finalQuery = wantsDocs ? (query == null || query.isBlank() ? message : query) : null;
        return new Planned(new Plan(calls, finalQuery, false), usage, dropped);
    }

    private static String stripFences(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = nl > 0 ? s.substring(nl + 1) : s;
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }
}
