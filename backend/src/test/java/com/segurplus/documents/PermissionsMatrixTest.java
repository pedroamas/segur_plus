package com.segurplus.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import com.segurplus.support.PermissionsOracle;
import com.segurplus.support.RestSupport;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Criterio 1 de F1: matriz usuario × documento × acción generada desde permissions-spec.yaml, ejecutada por REST contra
 * PostgreSQL y MinIO reales y comparada con un oráculo independiente. 7 usuarios × 32 documentos = 224 celdas; cada una prueba
 * detalle (READ), descarga (DOWNLOAD) y la auditoría. Los DENY deben ser 404.
 */
class PermissionsMatrixTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private final PermissionsOracle oracle = new PermissionsOracle();
    private RestSupport rest;
    private Map<String, UUID> idByCode;
    private final Map<String, String> userIdByCode = new HashMap<>();

    private void init() {
        rest = new RestSupport(mvc, mapper);
        idByCode = new HashMap<>();
        jdbc.queryForList("SELECT code, id FROM documents").forEach(r -> idByCode.put((String) r.get("code"), (UUID) r.get("id")));
        jdbc.queryForList("SELECT employee_code, id FROM users").forEach(r -> userIdByCode.put((String) r.get("employee_code"), r.get("id").toString()));
    }

    private int count(String action, String userCode, UUID docId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND user_id = ?::uuid AND resource_id = ?",
                Integer.class, action, userIdByCode.get(userCode), docId.toString());
    }

    @Test
    void seededFixturesMatchTheSpec() {
        init();
        assertThat(idByCode).hasSize(32).containsKeys(oracle.docs().keySet().toArray(String[]::new));
        assertThat(oracle.users()).hasSize(7);
        assertThat(oracle.docs().values()).allMatch(d -> d.allowedRoles().contains("MANAGER"));
        // Resultado por rol según la matriz: MANAGER 32, ACCOUNTANT 14, SALES_MANAGER 13, EMPLOYEE 10.
        assertThat(oracle.readable("DIR001")).hasSize(32);
        assertThat(oracle.readable("ACC001")).hasSize(14);
        assertThat(oracle.readable("COM001")).hasSize(13);
        for (String e : List.of("EMP001", "EMP002", "EMP003", "EMP004")) {
            assertThat(oracle.readable(e)).hasSize(10);
        }
    }

    @TestFactory
    Stream<DynamicTest> matrixUserByDocument() {
        init();
        AtomicInteger cells = new AtomicInteger();
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        AtomicInteger diffs = new AtomicInteger();
        List<DynamicTest> tests = new ArrayList<>();
        for (String user : oracle.users()) {
            for (String code : oracle.docs().keySet()) {
                tests.add(dynamicTest(user + " x " + code, () -> {
                    boolean expected = oracle.canRead(user, code);
                    UUID id = idByCode.get(code);
                    String auth = rest.bearer(user);
                    int deniedBefore = count("ACCESS_DENIED", user, id);
                    int downloadsBefore = count("DOCUMENT_DOWNLOAD", user, id);

                    MvcResult detail = mvc.perform(get("/api/v1/documents/" + id).header("Authorization", auth)).andReturn();
                    MvcResult download = mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", auth)).andReturn();
                    int detailStatus = detail.getResponse().getStatus();
                    int downloadStatus = download.getResponse().getStatus();
                    cells.incrementAndGet();
                    boolean ok = detailStatus == (expected ? 200 : 404) && downloadStatus == (expected ? 200 : 404);
                    if (expected) {
                        allowed.incrementAndGet();
                        JsonNode body = mapper.readTree(detail.getResponse().getContentAsString());
                        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(download.getResponse().getContentAsByteArray()));
                        ok &= code.equals(body.get("code").asText()) && sha.equals(body.get("checksumSha256").asText());
                        ok &= count("DOCUMENT_DOWNLOAD", user, id) == downloadsBefore + 1 && count("ACCESS_DENIED", user, id) == deniedBefore;
                    } else {
                        denied.incrementAndGet();
                        ok &= detail.getResponse().getContentType().startsWith("application/problem+json");
                        ok &= count("ACCESS_DENIED", user, id) == deniedBefore + 2 && count("DOCUMENT_DOWNLOAD", user, id) == downloadsBefore;
                    }
                    if (!ok) {
                        diffs.incrementAndGet();
                    }
                    assertThat(ok).as("%s x %s expected=%s detail=%d download=%d", user, code, expected, detailStatus, downloadStatus).isTrue();
                }));
            }
        }
        tests.add(dynamicTest("SUMMARY: 224 cells, 99 allowed, 125 denied, 0 diffs", () -> {
            System.out.printf("MATRIX cells=%d allowed=%d denied=%d diffs=%d%n", cells.get(), allowed.get(), denied.get(), diffs.get());
            assertThat(cells.get()).isEqualTo(224);
            assertThat(allowed.get()).isEqualTo(99);
            assertThat(denied.get()).isEqualTo(125);
            assertThat(diffs.get()).isZero();
        }));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> listingMatchesOraclePerUser() {
        init();
        return oracle.users().stream().map(user -> dynamicTest("list " + user, () -> {
            Set<String> seen = new LinkedHashSet<>();
            long total = -1;
            for (int page = 0; page < 5; page++) {
                JsonNode body = mapper.readTree(mvc.perform(get("/api/v1/documents?size=10&page=" + page).header("Authorization", rest.bearer(user)))
                        .andReturn().getResponse().getContentAsString());
                total = body.get("totalElements").asLong();
                body.get("items").forEach(i -> seen.add(i.get("code").asText()));
            }
            // El conteo sale de la misma consulta filtrada: nunca delata documentos ocultos.
            assertThat(total).isEqualTo(oracle.readable(user).size());
            assertThat(seen).isEqualTo(oracle.readable(user));
        }));
    }

    @Test
    void positiveControlsFromTheSpecAreAllowedForReadAndDownload() throws Exception {
        init();
        int checked = 0;
        for (var entry : oracle.positiveCases().entrySet()) {
            for (String code : entry.getValue()) {
                UUID id = idByCode.get(code);
                String auth = rest.bearer(entry.getKey());
                assertThat(mvc.perform(get("/api/v1/documents/" + id).header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(200);
                assertThat(mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(200);
                checked++;
            }
        }
        System.out.println("POSITIVE controls checked=" + checked);
        assertThat(checked).isEqualTo(6 + 7 + 3);
    }
}
