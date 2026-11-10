package com.segurplus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.AiIntegrationTest;
import com.segurplus.support.ChatSupport;
import com.segurplus.support.FakeDify;
import com.segurplus.support.RestSupport;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F6, criterio 4: comprobación de LOGS sobre un recorrido completo (login, refresh, logout, documentos, descarga, subida, chat con
 * herramientas y RAG, errores). Se siembran centinelas únicos en la pregunta, el texto del documento, el título, la respuesta del
 * «modelo» y las credenciales, y se exige que NINGUNO aparezca en la salida (consola/logs) ni en la auditoría. También se exige que
 * los logs SÍ lleven lo útil (método, ruta, estado, ID de correlación) y que la auditoría guarde metadatos.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends AiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentIndexer indexer;

    @Test
    void noJwtRefreshPasswordPromptAnswerOrDocumentContentReachesLogsOrAudit(CapturedOutput output) throws Exception {
        String qSecret = "PREGUNTA_SENTINELA_" + UUID.randomUUID();
        String docSecret = "CONTENIDO_SENTINELA_" + UUID.randomUUID();
        String titleSecret = "TITULO_SENTINELA_" + UUID.randomUUID();
        String answerSecret = "RESPUESTA_SENTINELA_" + UUID.randomUUID();
        String badPassword = "clave-mala-" + UUID.randomUUID();
        String correlation = UUID.randomUUID().toString();
        FakeDify.reset();
        jdbc.update("UPDATE documents SET index_status = 'PENDING', dify_dataset_id = NULL, dify_document_id = NULL, indexed_at = NULL");

        RestSupport rest = new RestSupport(mvc, mapper);
        ChatSupport chat = new ChatSupport(port, mapper, rest);
        MockHttpServletResponse login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).header("X-Correlation-Id", correlation)
                .content("{\"username\":\"DIR001\",\"password\":\"%s\"}".formatted(SEED_PASSWORD))).andReturn().getResponse();
        String jwt = mapper.readTree(login.getContentAsString()).get("accessToken").asText();
        String refresh = login.getCookie("segurplus_refresh").getValue();
        String auth = "Bearer " + jwt;
        List<String> sensitive = new ArrayList<>(List.of(jwt, refresh, SEED_PASSWORD, badPassword, qSecret, docSecret, titleSecret, answerSecret, jwt.split("\\.")[1]));

        // subida con título y contenido centinela → indexado (create-by-text) → recuperado
        String content = mapper.writeValueAsString(Map.of("contenido", docSecret));
        int up = mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "x.json", "application/json", content.getBytes(StandardCharsets.UTF_8)))
                .param("code", "ZZ77").param("title", titleSecret).param("kb", "GENERAL").param("category", "MANUAL").param("classification", "INTERNAL")
                .param("allowedRoles", "MANAGER").param("allowedRoles", "EMPLOYEE").header("Authorization", auth).header("X-Correlation-Id", correlation)).andReturn().getResponse().getStatus();
        assertThat(up).isEqualTo(201);
        indexer.runOnce();
        UUID id = jdbc.queryForObject("SELECT id FROM documents WHERE code = 'ZZ77'", UUID.class);
        String dify = jdbc.queryForObject("SELECT dify_document_id FROM documents WHERE code = 'ZZ77'", String.class);
        FakeDify.stubRetrieve("ds-general", List.of(new FakeDify.Frag(dify, id.toString(), "seg-zz77", docSecret, 0.9)));
        FakeDify.stubPlan("{\"retrieve\":{\"query\":\"" + qSecret + "\"},\"tools\":[{\"name\":\"get_sales_summary\",\"params\":{\"period\":\"MONTH:2026-10\"}}]}");
        FakeDify.stubGeneration("Según el documento [ZZ77]: " + answerSecret, 10, 5);

        mvc.perform(get("/api/v1/users/me").header("Authorization", auth).header("X-Correlation-Id", correlation));
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", auth));
        mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", auth));
        String pdf = jdbc.queryForObject("SELECT id::text FROM documents WHERE file_type = 'PDF' AND code NOT LIKE 'ZZ%' ORDER BY code LIMIT 1", String.class);
        mvc.perform(get("/api/v1/documents/" + pdf + "/view").header("Authorization", auth));   // DOCUMENT_READ solo lo emiten visor y preview
        ChatSupport.Result r = chat.send("DIR001", mapper.createObjectNode().put("message", qSecret).toString());
        assertThat(r.first("done")).isNotNull();
        assertThat(r.text()).contains(answerSecret);   // el contenido SÍ viaja al usuario; solo no se registra
        chat.send("EMP001", mapper.createObjectNode().put("message", qSecret).put("employee_id", "EMP002").toString());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("segurplus_refresh", refresh)));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("segurplus_refresh", refresh)));   // reutilización
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"DIR001\",\"password\":\"" + badPassword + "\"}"));
        mvc.perform(get("/api/v1/documents/" + UUID.randomUUID()).header("Authorization", auth));
        FakeDify.stubGenerationStatus(500);
        chat.send("DIR001", mapper.createObjectNode().put("message", qSecret + " (error de Dify)").toString());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("segurplus_refresh", refresh)));

        String logs = output.getAll();
        assertThat(logs).contains("segurplus.http").contains(correlation).contains("/api/v1/chat").contains("-> 200");
        for (String s : sensitive) {
            assertThat(logs).as("logs sin " + s.substring(0, Math.min(14, s.length()))).doesNotContain(s);
        }
        assertThat(logs).doesNotContain("Bearer ey").doesNotContain(FakeDify.APP_KEY).doesNotContain(FakeDify.KNOWLEDGE_KEY).doesNotContain(FakeDify.PLANNER_KEY)
                .doesNotContain("Authorization:").doesNotContain("Cookie:");

        // la auditoría: metadatos (acción, usuario, recurso, decisión, motivo, canal, correlación, contadores), nunca contenido
        String audit = jdbc.queryForList("SELECT action || '|' || coalesce(resource_type,'') || '|' || coalesce(resource_id,'') || '|' || coalesce(reason_code,'') || '|'"
                + " || coalesce(channel,'') || '|' || coalesce(correlation_id,'') || '|' || details::text FROM audit_logs", String.class).toString();
        for (String s : sensitive) {
            assertThat(audit).as("auditoría sin " + s.substring(0, Math.min(14, s.length()))).doesNotContain(s);
        }
        Map<String, Object> download = jdbc.queryForMap("SELECT user_id::text AS u, resource_type, resource_id, decision, correlation_id FROM audit_logs WHERE action = 'DOCUMENT_DOWNLOAD' AND resource_id = ?", id.toString());
        assertThat(download.get("u")).isEqualTo(jdbc.queryForObject("SELECT id::text FROM users WHERE employee_code = 'DIR001'", String.class));
        assertThat(download.get("decision")).isEqualTo("ALLOW");
        assertThat(download.get("correlation_id")).isNotNull();
        for (String action : List.of("LOGIN", "LOGIN_FAILED", "TOKEN_REFRESH", "TOKEN_REUSE_DETECTED", "DOCUMENT_UPLOAD", "DOCUMENT_READ", "CHAT", "TOOL_CALL", "IGNORED_CLIENT_FIELD", "ACCESS_DENIED")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ?", Integer.class, action)).as("auditoría " + action).isPositive();
        }
        // el texto de la conversación solo vive en messages (propietario), no en el resto de tablas
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs a WHERE a::text LIKE '%' || ? || '%'", Integer.class, qSecret)).isZero();
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM conversations");
    }
}
