package com.segurplus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.AiIntegrationTest;
import com.segurplus.support.ChatSupport;
import com.segurplus.support.FakeDify;
import com.segurplus.support.OctoberDataset;
import com.segurplus.support.RestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F4: las 4 herramientas tipadas son fachadas de los servicios de {@code data} (los mismos que el dashboard de F3). Con la
 * planificación SIMULADA, comprueba que las cifras de octubre que recibe el LLM coinciden con el dashboard (12 / 9.680 € /
 * 968 € / 80,7 %), que el alcance sale del JWT y que ningún parámetro de identidad propuesto por el LLM tiene efecto.
 */
class ChatToolsTest extends AiIntegrationTest {

    private static final Pattern TOOL_BLOCK = Pattern.compile("<<<HERRAMIENTA (\\w+) [0-9a-f]{8}>>>\\n(.*?)\\n<<<FIN", Pattern.DOTALL);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private OctoberDataset data;
    private RestSupport rest;
    private ChatSupport chat;

    @BeforeEach
    void setUp() {
        data = new OctoberDataset(jdbc);
        data.load();
        rest = new RestSupport(mvc, mapper);
        chat = new ChatSupport(port, mapper, rest);
        FakeDify.reset();
        jdbc.update("DELETE FROM audit_logs WHERE action = 'TOOL_CALL'");
    }

    @AfterEach
    void tearDown() {
        data.clean();
    }

    private static String plan(String tools) {
        return "{\"retrieve\":null,\"tools\":" + tools + "}";
    }

    private Map<String, JsonNode> toolOutputs() throws Exception {
        List<JsonNode> reqs = FakeDify.chatRequests(FakeDify.APP_KEY);
        String prompt = reqs.get(reqs.size() - 1).get("query").asText();
        Map<String, JsonNode> out = new HashMap<>();
        Matcher m = TOOL_BLOCK.matcher(prompt);
        while (m.find()) {
            out.put(m.group(1), mapper.readTree(m.group(2)));
        }
        return out;
    }

    private JsonNode dashboard(String user, String month) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/dashboard?month=" + month).header("Authorization", rest.bearer(user))).andReturn().getResponse().getContentAsString());
    }

    @Test
    void octoberFiguresFromTheToolsMatchTheDatabaseAndTheDashboard() throws Exception {
        FakeDify.stubPlan(plan("""
                [{"name":"get_targets","params":{"period":"MONTH:2026-10"}},{"name":"get_sales_summary","params":{"period":"MONTH:2026-10"}},
                 {"name":"get_commissions","params":{"period":"MONTH:2026-10"}},{"name":"get_policies_by_status","params":{"period":"MONTH:2026-10"}}]"""));
        ChatSupport.Result r = chat.ask("DIR001", "Resumen de octubre");
        assertThat(r.first("done")).isNotNull();
        Map<String, JsonNode> t = toolOutputs();
        assertThat(t.keySet()).containsExactlyInAnyOrder("get_targets", "get_sales_summary", "get_commissions", "get_policies_by_status");
        // las cifras ancla
        assertThat(t.get("get_sales_summary").at("/totals/policies").asInt()).isEqualTo(12);
        assertThat(t.get("get_sales_summary").at("/totals/premiums").asText()).isEqualTo("9680.00");
        assertThat(t.get("get_commissions").at("/totals/accrued").asText()).isEqualTo("968.00");
        assertThat(t.get("get_targets").at("/totals/compliancePercent").asText()).isEqualTo("80.7");
        assertThat(t.get("get_targets").at("/totals/premiums").asText()).isEqualTo("9680.00");
        assertThat(t.get("get_targets").at("/totals/target").asText()).isEqualTo("12000.00");
        // y son EXACTAMENTE las del dashboard (mismos servicios)
        JsonNode d = dashboard("DIR001", "2026-10");
        assertThat(t.get("get_sales_summary").at("/totals/policies").asInt()).isEqualTo(d.at("/sales/policyCount").asInt());
        assertThat(t.get("get_sales_summary").at("/totals/premiums").asText()).isEqualTo(d.at("/sales/premiums/amount").asText());
        assertThat(t.get("get_commissions").at("/totals/accrued").asText()).isEqualTo(d.at("/commissions/accrued/amount").asText());
        assertThat(t.get("get_commissions").at("/totals/net").asText()).isEqualTo(d.at("/commissions/net/amount").asText());
        assertThat(t.get("get_policies_by_status").at("/totals/active").asInt()).isEqualTo(d.at("/policiesByStatus/active").asInt());
        assertThat(t.get("get_policies_by_status").at("/totals/cancelled").asInt()).isEqualTo(d.at("/policiesByStatus/cancelled").asInt());
        assertThat(t.get("get_targets").at("/totals/compliancePercent").asText()).isEqualTo(d.at("/targets/percent").asText());
        // la verificación acepta las cifras de las herramientas
        FakeDify.stubGeneration("El cumplimiento de octubre fue del 80,7 % (9.680 € sobre 12.000 €), con 12 pólizas y 968 € de comisiones.", 10, 10);
        JsonNode v = chat.ask("DIR001", "Resumen de octubre").first("verification");
        assertThat(v.get("unsupportedNumbers")).as("80,7 / 9.680 / 12.000 / 968 salen de las herramientas").isEmpty();
        // el resultado se guarda en el mensaje (rows) y la auditoría solo lleva metadatos
        String stored = jdbc.queryForObject("SELECT tools::text FROM messages WHERE role = 'ASSISTANT' ORDER BY created_at DESC LIMIT 1", String.class);
        assertThat(stored).contains("get_targets", "9680.00");
        String audit = jdbc.queryForList("SELECT details::text FROM audit_logs WHERE action = 'TOOL_CALL'", String.class).toString();
        assertThat(audit).doesNotContain("9680").doesNotContain("968");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOOL_CALL' AND channel = 'AI_TOOL'", Integer.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void employeeIdOrRoleProposedByTheModelHasNoEffectAndTheScopeComesFromTheJwt() throws Exception {
        UUID emp2 = jdbc.queryForObject("SELECT id FROM users WHERE employee_code = 'EMP002'", UUID.class);
        FakeDify.stubPlan(plan("""
                [{"name":"get_sales_summary","params":{"period":"MONTH:2026-10","employee_id":"%s","role":"MANAGER","employee_code":"EMP002"}},
                 {"name":"get_targets","params":{"period":"MONTH:2026-10","employee_id":"%s"}},
                 {"name":"run_sql","params":{"query":"select * from users"}},
                 {"name":"get_salary","params":{}}]""".formatted(emp2, emp2)));
        chat.ask("EMP001", "¿Cuánto ha vendido EMP002?");
        Map<String, JsonNode> t = toolOutputs();
        assertThat(t.keySet()).as("run_sql y get_salary no existen").containsExactlyInAnyOrder("get_sales_summary", "get_targets");
        assertThat(t.get("get_sales_summary").at("/totals/premiums").asText()).as("EMP001 ve lo suyo").isEqualTo("3150.00");
        assertThat(t.get("get_sales_summary").at("/params").toString()).doesNotContain("employee").doesNotContain("role");
        assertThat(t.get("get_targets").at("/rows")).hasSize(1);
        assertThat(t.get("get_targets").at("/rows/0/employee").asText()).isEqualTo("EMP001");
        List<JsonNode> reqs = FakeDify.chatRequests(FakeDify.APP_KEY);
        String prompt = reqs.get(reqs.size() - 1).get("query").asText();
        assertThat(prompt).doesNotContain("2280.00").doesNotContain("EMP002\"").doesNotContain(emp2.toString()).doesNotContain("monthly_salary");
    }

    @Test
    void everyRoleGetsItsOwnScope() throws Exception {
        FakeDify.stubPlan(plan("""
                [{"name":"get_sales_summary","params":{"period":"MONTH:2026-09"}},{"name":"get_commissions","params":{"period":"MONTH:2026-10"}},
                 {"name":"get_targets","params":{"period":"MONTH:2026-10"}}]"""));
        // SALES_MANAGER: TEAM (los 4 comerciales; la póliza de septiembre de COM001 no cuenta); 968 € de comisiones; 4 filas de objetivos
        chat.ask("COM001", "equipo");
        Map<String, JsonNode> com = toolOutputs();
        assertThat(com.get("get_sales_summary").at("/totals/premiums").asText()).isEqualTo("500.00");
        assertThat(com.get("get_commissions").at("/totals/accrued").asText()).isEqualTo("968.00");
        assertThat(com.get("get_targets").at("/rows")).hasSize(4);
        // MANAGER: GLOBAL (incluye la póliza de COM001)
        chat.ask("DIR001", "todo");
        assertThat(toolOutputs().get("get_sales_summary").at("/totals/premiums").asText()).isEqualTo("1277.00");
        // ACCOUNTANT: agregados; get_targets no está en su perfil (el planificador lo propone y se descarta)
        chat.ask("ACC001", "agregados");
        Map<String, JsonNode> acc = toolOutputs();
        assertThat(acc.keySet()).containsExactlyInAnyOrder("get_sales_summary", "get_commissions");
        assertThat(acc.get("get_commissions").at("/totals/accrued").asText()).isEqualTo("968.00");
        assertThat(acc.toString()).doesNotContain("EMP00");
        // EMPLOYEE: OWN en cada herramienta
        for (String emp : OctoberDataset.EMPLOYEES) {
            chat.ask(emp, "lo mío");
            Map<String, JsonNode> own = toolOutputs();
            assertThat(own.get("get_targets").at("/rows")).hasSize(1);
            assertThat(own.get("get_targets").at("/rows/0/employee").asText()).isEqualTo(emp);
            assertThat(own.get("get_commissions").at("/totals/accrued").asText())
                    .isEqualTo(Map.of("EMP001", "315.00", "EMP002", "228.00", "EMP003", "205.00", "EMP004", "220.00").get(emp));
        }
    }

    @Test
    void periodsAreResolvedWithTheBusinessClockAndInvalidOnesAreRejected() throws Exception {
        // reloj fijo 2027-01-15: LAST_MONTH = 2026-12 (una póliza de EMP003 de 300 €); THIS_MONTH = 2027-01 (sin datos)
        FakeDify.stubPlan(plan("""
                [{"name":"get_sales_summary","params":{"period":"LAST_MONTH"}},{"name":"get_commissions","params":{"period":"nonsense"}}]"""));
        chat.ask("DIR001", "mes pasado");
        Map<String, JsonNode> t = toolOutputs();
        assertThat(t.get("get_sales_summary").at("/params/period").asText()).isEqualTo("LAST_MONTH");
        assertThat(t.get("get_sales_summary").at("/rows/0/month").asText()).isEqualTo("2026-12");
        assertThat(t.get("get_sales_summary").at("/totals/premiums").asText()).isEqualTo("300.00");
        assertThat(t.get("get_sales_summary").get("asOf").asText()).isEqualTo("2027-01-15");
        assertThat(t.get("get_commissions").get("note").asText()).isEqualTo("Invalid period.");
    }

    @Test
    void unusablePlansFallBackToDocumentSearchAndDifyPlannerDownIsTolerated() throws Exception {
        FakeDify.stubPlan("esto no es JSON");
        assertThat(chat.ask("EMP001", "¿qué cubre el seguro de hogar?").first("done")).isNotNull();
        assertThat(FakeDify.requestsTo("/v1/datasets/ds-general/retrieve")).as("sin plan válido se busca en documentos con la pregunta").isNotEmpty();
        assertThat(FakeDify.requestsTo("/v1/datasets/ds-general/retrieve").get(0).get("query").asText()).isEqualTo("¿qué cubre el seguro de hogar?");
        FakeDify.SERVER.resetRequests();
        FakeDify.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/chat-messages"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer " + FakeDify.PLANNER_KEY))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(503)));
        assertThat(chat.ask("EMP001", "otra").first("done")).as("planificador caído: se degrada a búsqueda documental").isNotNull();
    }
}
