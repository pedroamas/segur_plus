package com.segurplus.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** ID de correlación, logs sin JWT ni contenido, Problem Details y OpenAPI con esquema de seguridad. */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityAndOpenApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void logsNeverContainJwtRefreshTokenPasswordOrDocumentContent(CapturedOutput output) throws Exception {
        String correlation = UUID.randomUUID().toString();
        MockHttpServletResponse login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", correlation)
                        .content("{\"username\":\"EMP001\",\"password\":\"%s\"}".formatted(SEED_PASSWORD))).andReturn().getResponse();
        String jwt = mapper.readTree(login.getContentAsString()).get("accessToken").asText();
        String refresh = login.getCookie("segurplus_refresh").getValue();

        UUID d14 = jdbc.queryForObject("SELECT id FROM documents WHERE code = 'D14'", UUID.class);
        UUID d01 = jdbc.queryForObject("SELECT id FROM documents WHERE code = 'D01'", UUID.class);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + jwt).header("X-Correlation-Id", correlation));
        mvc.perform(get("/api/v1/documents/" + d14 + "/download").header("Authorization", "Bearer " + jwt).header("X-Correlation-Id", correlation));
        mvc.perform(get("/api/v1/documents/" + d01).header("Authorization", "Bearer " + jwt).header("X-Correlation-Id", correlation));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("segurplus_refresh", refresh)).header("X-Correlation-Id", correlation));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"EMP001\",\"password\":\"clave-incorrecta-xyz\"}"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + jwt.substring(0, jwt.length() - 3) + "abc"));

        String logs = output.getAll();
        assertThat(logs).contains("segurplus.http").contains(correlation);
        assertThat(logs).doesNotContain(jwt).doesNotContain(refresh).doesNotContain(SEED_PASSWORD).doesNotContain("clave-incorrecta-xyz")
                .doesNotContain(jwt.split("\\.")[1]).doesNotContain("Fixture D14").doesNotContain("Bearer ey");
    }

    @Test
    void correlationIdIsEchoedGeneratedAndStoredInAudit() throws Exception {
        String correlation = UUID.randomUUID().toString();
        MockHttpServletResponse bad = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", correlation).content("{\"username\":\"NOBODY\",\"password\":\"x\"}")).andReturn().getResponse();
        assertThat(bad.getHeader("X-Correlation-Id")).isEqualTo(correlation);
        assertThat(mapper.readTree(bad.getContentAsString()).get("correlationId").asText()).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE correlation_id = ? AND action = 'LOGIN_FAILED'", Integer.class, correlation)).isEqualTo(1);

        // Valor sospechoso (inyección en logs, demasiado largo) ⇒ se descarta y se genera uno nuevo.
        for (String evil : new String[] {"a\nb", "x".repeat(80), "bad value!", "short"}) {
            String echoed = mvc.perform(get("/api/v1/users/me").header("X-Correlation-Id", evil.replace("\n", " "))).andReturn().getResponse().getHeader("X-Correlation-Id");
            assertThat(echoed).isNotEqualTo(evil).matches("[0-9a-f-]{36}");
        }
        assertThat(mvc.perform(get("/api/v1/users/me")).andReturn().getResponse().getHeader("X-Correlation-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void errorsAreProblemDetailsAndNeverLeakInternals() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/v1/documents/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + login("EMP001"))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        JsonNode pd = mapper.readTree(r.getContentAsString());
        assertThat(pd.fieldNames()).toIterable().contains("type", "title", "status", "detail", "instance", "correlationId");
        assertThat(pd.get("type").asText()).isEqualTo("urn:segurplus:problem:not-found");
        assertThat(r.getContentAsString()).doesNotContain("Exception").doesNotContain("org.springframework").doesNotContain("SELECT");

        // Ruta inexistente y método no permitido también salen como Problem Details.
        MockHttpServletResponse nf = mvc.perform(get("/api/v1/nada").header("Authorization", "Bearer " + login("EMP001"))).andReturn().getResponse();
        assertThat(nf.getStatus()).isEqualTo(404);
        assertThat(nf.getContentType()).startsWith("application/problem+json");
        MockHttpServletResponse bad = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(bad.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void openApiDeclaresBearerSecuritySchemeAndAllEndpoints() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode api = mapper.readTree(r.getContentAsString());
        JsonNode scheme = api.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.get("type").asText()).isEqualTo("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(api.get("paths").fieldNames()).toIterable().contains("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                "/api/v1/users/me", "/api/v1/documents", "/api/v1/documents/{id}", "/api/v1/documents/{id}/view",
                "/api/v1/documents/{id}/preview", "/api/v1/documents/{id}/download");
        // Los endpoints de sesión son públicos (security: []); los de datos exigen bearerAuth.
        assertThat(api.at("/paths/~1api~1v1~1auth~1login/post/security").size()).isZero();
        assertThat(api.at("/paths/~1api~1v1~1documents~1{id}/get/security").size()).isZero(); // hereda el requisito global
        assertThat(api.get("security").toString()).contains("bearerAuth");
    }

    private String login(String code) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(code, SEED_PASSWORD))).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("accessToken").asText();
    }
}
