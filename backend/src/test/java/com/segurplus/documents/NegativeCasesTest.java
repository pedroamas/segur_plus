package com.segurplus.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import com.segurplus.support.PermissionsOracle;
import com.segurplus.support.RestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Casos negativos obligatorios (permissions-mapping §4) por REST: NEG-01, 02, 04, 06 (celdas usuario × documento), NEG-07
 * (ID alterado) y NEG-08 (campos de identidad del cliente). NEG-03 y NEG-12 (conversaciones) no son ejercitables en F1: las
 * conversaciones y su API pertenecen a F4 (ver informe).
 */
class NegativeCasesTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private final PermissionsOracle oracle = new PermissionsOracle();

    private static List<String> range(String prefix, int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "%s%02d".formatted(prefix, i)).toList();
    }

    /** Celdas negativas literales de NEG-01, 02, 04 y 06, deduplicadas por (usuario, documento). */
    static Set<String> negativeCells() {
        Set<String> cells = new LinkedHashSet<>();
        range("D", 17, 25).forEach(d -> cells.add("EMP001:" + d));                      // NEG-01
        range("D", 1, 13).forEach(d -> cells.add("EMP001:" + d));                       // NEG-02 (D11 y D01-D13)
        range("D", 1, 6).forEach(d -> cells.add("ACC001:" + d));                        // NEG-04
        range("D", 14, 25).forEach(d -> cells.add("ACC001:" + d));                      // NEG-04 (contratos y nóminas)
        range("D", 1, 5).forEach(d -> cells.add("COM001:" + d));                        // NEG-06
        List.of("D14", "D16", "D17", "D19", "D20", "D22", "D23", "D25").forEach(d -> cells.add("COM001:" + d));
        return cells;
    }

    private Map<String, UUID> ids() {
        Map<String, UUID> ids = new HashMap<>();
        jdbc.queryForList("SELECT code, id FROM documents").forEach(r -> ids.put((String) r.get("code"), (UUID) r.get("id")));
        return ids;
    }

    @TestFactory
    Stream<DynamicTest> negativeCellsAre404InEveryRestChannel() {
        Map<String, UUID> ids = ids();
        RestSupport rest = new RestSupport(mvc, mapper);
        AtomicInteger checked = new AtomicInteger();
        List<DynamicTest> tests = new ArrayList<>();
        for (String cell : negativeCells()) {
            String user = cell.split(":")[0];
            String code = cell.split(":")[1];
            tests.add(dynamicTest(cell, () -> {
                assertThat(oracle.canRead(user, code)).as("the oracle must also deny %s", cell).isFalse();
                String auth = rest.bearer(user);
                UUID id = ids.get(code);
                assertThat(mvc.perform(get("/api/v1/documents/" + id).header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(404);
                assertThat(mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(404);
                assertThat(mvc.perform(get("/api/v1/documents/" + id + "/view").header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(404);
                assertThat(mvc.perform(get("/api/v1/documents/" + id + "/preview").header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(404);
                JsonNode list = mapper.readTree(mvc.perform(get("/api/v1/documents?size=100").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
                list.get("items").forEach(i -> assertThat(i.get("code").asText()).isNotEqualTo(code));
                checked.incrementAndGet();
            }));
        }
        tests.add(dynamicTest("SUMMARY negative cells", () -> {
            System.out.println("NEGATIVE cells (NEG-01/02/04/06, distinct user x document) checked=" + checked.get());
            assertThat(checked.get()).isEqualTo(negativeCells().size());
        }));
        return tests.stream();
    }

    @Test
    void alteredIdsGiveTheSame404AsADeniedDocument() throws Exception {
        RestSupport rest = new RestSupport(mvc, mapper);
        Map<String, UUID> ids = ids();
        for (String user : oracle.users()) {
            String auth = rest.bearer(user);
            String denied = null;
            for (String code : oracle.docs().keySet()) {
                if (!oracle.canRead(user, code)) {
                    denied = mvc.perform(get("/api/v1/documents/" + ids.get(code)).header("Authorization", auth)).andReturn().getResponse().getContentAsString();
                    break;
                }
            }
            String randomUuid = UUID.randomUUID().toString();
            String mutated = ids.get("D01").toString().replaceFirst(".$", ids.get("D01").toString().endsWith("0") ? "1" : "0");
            for (String altered : List.of(randomUuid, mutated, "not-a-uuid", "1", "undefined")) {
                var res = mvc.perform(get("/api/v1/documents/" + altered).header("Authorization", auth)).andReturn().getResponse();
                assertThat(res.getStatus()).as("%s %s", user, altered).isEqualTo(404);
                if (denied != null) {
                    // Mismo cuerpo salvo instance y correlationId: un denegado no se distingue de un inexistente.
                    assertThat(normalize(res.getContentAsString())).isEqualTo(normalize(denied));
                }
            }
        }
    }

    private String normalize(String json) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
        node.remove("correlationId");
        node.remove("instance");
        return node.toString();
    }

    @Test
    void clientSuppliedIdentityFieldsDoNotChangeTheResult() throws Exception {
        RestSupport rest = new RestSupport(mvc, mapper);
        String emp = rest.bearer("EMP001");
        Map<String, UUID> ids = ids();
        String tampering = "?employee_id=DIR001&role=MANAGER&assistantId=a1&datasetId=d1&userId=" + UUID.randomUUID();

        Set<String> plain = codes(mvc.perform(get("/api/v1/documents?size=100").header("Authorization", emp)).andReturn().getResponse().getContentAsString());
        Set<String> tampered = codes(mvc.perform(get("/api/v1/documents" + tampering + "&size=100").header("Authorization", emp)
                .header("X-Role", "MANAGER").header("X-Employee-Id", "DIR001")).andReturn().getResponse().getContentAsString());
        assertThat(tampered).isEqualTo(plain).isEqualTo(oracle.readable("EMP001"));

        for (String code : List.of("D01", "D11", "D17", "D18")) {
            assertThat(mvc.perform(get("/api/v1/documents/" + ids.get(code) + tampering).header("Authorization", emp)
                    .header("X-Role", "MANAGER")).andReturn().getResponse().getStatus()).as(code).isEqualTo(404);
            assertThat(mvc.perform(get("/api/v1/documents/" + ids.get(code) + "/download" + tampering).header("Authorization", emp))
                    .andReturn().getResponse().getStatus()).as(code).isEqualTo(404);
        }
        assertThat(mvc.perform(get("/api/v1/documents/" + ids.get("D14") + tampering).header("Authorization", emp)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'IGNORED_CLIENT_FIELD' AND details->'fields' @> '[\"assistantid\",\"datasetid\",\"employee_id\",\"role\"]'::jsonb",
                Integer.class)).isPositive();
    }

    private Set<String> codes(String pageJson) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        mapper.readTree(pageJson).get("items").forEach(i -> out.add(i.get("code").asText()));
        return out;
    }
}
