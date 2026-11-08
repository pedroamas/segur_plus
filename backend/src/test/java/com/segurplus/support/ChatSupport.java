package com.segurplus.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Cliente de pruebas del chat: lanza el POST, espera a que el servidor cierre el SSE y devuelve los eventos ya parseados. */
public final class ChatSupport {

    public record Event(String name, JsonNode data) {
    }

    public record Result(int status, String raw, List<Event> events) {

        public List<Event> named(String name) {
            return events.stream().filter(e -> e.name().equals(name)).toList();
        }

        public JsonNode first(String name) {
            return named(name).stream().findFirst().map(Event::data).orElse(null);
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            named("delta").forEach(e -> sb.append(e.data().get("text").asText()));
            return sb.toString();
        }
    }

    private final int port;
    private final ObjectMapper mapper;
    private final RestSupport rest;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Habla por HTTP REAL con el servidor embebido (Tomcat, filtros y despacho asíncrono incluidos), como lo haría curl. */
    public ChatSupport(int port, ObjectMapper mapper, RestSupport rest) {
        this.port = port;
        this.mapper = mapper;
        this.rest = rest;
    }

    public Result send(String user, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat"))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (user != null) {
            b.header("Authorization", rest.bearer(user));
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(res.statusCode(), res.body(), parse(res.body()));
    }

    public Result ask(String user, String message) throws Exception {
        return send(user, mapper.createObjectNode().put("message", message).toString());
    }

    private List<Event> parse(String raw) throws Exception {
        List<Event> events = new ArrayList<>();
        for (String block : raw.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (name != null && data.length() > 0) {
                events.add(new Event(name, mapper.readTree(data.toString())));
            }
        }
        return events;
    }
}
