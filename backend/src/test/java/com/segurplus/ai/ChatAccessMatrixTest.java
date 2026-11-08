package com.segurplus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.segurplus.support.AiIntegrationTest;
import com.segurplus.support.ChatSupport;
import com.segurplus.support.FakeDify;
import com.segurplus.support.PermissionsOracle;
import com.segurplus.support.RestSupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F4: matriz de acceso del chat con Dify SIMULADO que devuelve el CORPUS COMPLETO (los 32 documentos) ignorando cualquier
 * filtro de metadatos (el peor caso). Lo que ve el LLM (el mensaje enviado a la app de generación) y lo que se cita deben
 * ser EXACTAMENTE lo que el oráculo independiente permite leer: cero fugas. Cada documento lleva un «canario» único.
 */
class ChatAccessMatrixTest extends AiIntegrationTest {

    private static final Map<String, Set<String>> KBS = Map.of(
            "MANAGER", Set.of("GENERAL", "DIRECCION", "FINANZAS", "EMPLEADOS"),
            "ACCOUNTANT", Set.of("GENERAL", "FINANZAS"),
            "SALES_MANAGER", Set.of("GENERAL", "DIRECCION", "FINANZAS", "EMPLEADOS"),
            "EMPLOYEE", Set.of("GENERAL", "EMPLEADOS"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentIndexer indexer;

    private final PermissionsOracle oracle = new PermissionsOracle();
    private RestSupport rest;
    private ChatSupport chat;

    record Doc(UUID id, String code, String kb, String difyId) { }

    @BeforeEach
    void indexAndStubTheFullCorpus() {
        rest = new RestSupport(mvc, mapper);
        chat = new ChatSupport(port, mapper, rest);
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM audit_logs WHERE action IN ('FRAGMENT_DROPPED','CHAT','IGNORED_CLIENT_FIELD')");
        FakeDify.reset();
        jdbc.update("UPDATE documents SET index_status = 'PENDING', dify_dataset_id = NULL, dify_document_id = NULL, indexed_at = NULL");
        indexer.runOnce();
        stubFullCorpus(docs());
    }

    private List<Doc> docs() {
        return jdbc.query("SELECT id, code, kb, dify_document_id FROM documents WHERE deleted_at IS NULL AND code NOT LIKE 'ZZ%' ORDER BY code",
                (rs, n) -> new Doc(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("kb"), rs.getString("dify_document_id")));
    }

    /** Cada dataset devuelve TODOS sus documentos, con un canario en el texto, sin mirar ningún filtro. */
    private static void stubFullCorpus(List<Doc> docs) {
        FakeDify.DATASETS.forEach((kb, ds) -> {
            List<FakeDify.Frag> frags = new ArrayList<>();
            double score = 0.99;
            for (Doc d : docs) {
                if (d.kb().equals(kb)) {
                    frags.add(new FakeDify.Frag(d.difyId(), d.id().toString(), "seg-" + d.code(), "CANARY_" + d.code() + " contenido del documento " + d.code(), score));
                    score -= 0.01;
                }
            }
            FakeDify.stubRetrieve(ds, frags);
        });
    }

    private String lastPrompt() {
        List<JsonNode> reqs = FakeDify.chatRequests(FakeDify.APP_KEY);
        assertThat(reqs).isNotEmpty();
        return reqs.get(reqs.size() - 1).get("query").asText();
    }

    private Set<String> canariesIn(String text) {
        return docs().stream().map(Doc::code).filter(c -> text.contains("CANARY_" + c + " ")).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> expectedFor(String user) {
        Set<String> kbs = KBS.get(oracle.roleOf(user));
        Set<String> inKbs = docs().stream().filter(d -> kbs.contains(d.kb())).map(Doc::code).collect(Collectors.toSet());
        Set<String> out = new LinkedHashSet<>(oracle.readable(user));
        out.retainAll(inKbs);
        return out;
    }

    @Test
    void theLlmOnlySeesWhatTheOracleAllowsForEveryUserOfTheSeed() throws Exception {
        for (String user : oracle.users()) {
            FakeDify.SERVER.resetRequests();
            ChatSupport.Result r = chat.ask(user, "¿Qué dicen los documentos?");
            assertThat(r.status()).as(user).isEqualTo(200);
            Set<String> expected = expectedFor(user);
            // (1) lo que llega al LLM
            assertThat(canariesIn(lastPrompt())).as("prompt de " + user).isEqualTo(expected);
            // (2) las fuentes que recibe el cliente
            Set<String> sourceCodes = new LinkedHashSet<>();
            r.first("sources").get("sources").forEach(s -> sourceCodes.add(s.get("code").asText()));
            assertThat(sourceCodes).as("fuentes de " + user).isEqualTo(expected);
            // (3) las fuentes persistidas y releídas por GET (re-autorizadas al leer)
            UUID conv = UUID.fromString(r.first("conversation").get("conversationId").asText());
            String body = mvc.perform(get("/api/v1/chat/conversations/" + conv + "/messages").header("Authorization", rest.bearer(user)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Set<String> persisted = new LinkedHashSet<>();
            for (JsonNode m : mapper.readTree(body)) {
                m.get("sources").forEach(s -> persisted.add(s.get("code").asText()));
            }
            assertThat(persisted).as("fuentes guardadas de " + user).isEqualTo(expected);
            // (4) cada descarte queda auditado con metadatos (sin contenido)
            int inKbs = (int) docs().stream().filter(d -> KBS.get(oracle.roleOf(user)).contains(d.kb())).count();
            Integer dropped = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'FRAGMENT_DROPPED' AND user_id = "
                    + "(SELECT id FROM users WHERE employee_code = ?) AND decision = 'DENY' AND channel = 'RAG'", Integer.class, user);
            assertThat(dropped).as("FRAGMENT_DROPPED de " + user).isEqualTo(inKbs - expected.size());
            jdbc.update("DELETE FROM audit_logs WHERE action = 'FRAGMENT_DROPPED'");
        }
    }

    @Test
    void specificNegativeCasesOfThePermissionsMatrix() throws Exception {
        // NEG-01/02: un EMPLOYEE no obtiene contenido de otro empleado, de Dirección ni de nóminas ajenas
        String emp = lastPromptFor("EMP001");
        assertThat(canariesIn(emp)).contains("D14", "D15", "D16").doesNotContain("D17", "D18", "D19", "D20", "D22", "D23", "D25", "D01", "D02", "D04", "D11");
        // NEG-04/05: ACC001 no ve Dirección ni contratos/nóminas
        String acc = lastPromptFor("ACC001");
        assertThat(canariesIn(acc)).doesNotContain("D01", "D02", "D04", "D05", "D14", "D16", "D17", "D19", "D20", "D22", "D23", "D25").contains("D07", "D12", "D13");
        // NEG-06: COM001 no ve D01-D05 ni contratos/nóminas, y sí D06, D11, D15, D18, D21, D24
        String com = lastPromptFor("COM001");
        assertThat(canariesIn(com)).doesNotContain("D01", "D02", "D03", "D04", "D05", "D14", "D16", "D17", "D19", "D20", "D22", "D23", "D25")
                .contains("D06", "D11", "D15", "D18", "D21", "D24");
        // un EMP no ve a otro EMP (36 cruces del seed): nada del resto
        for (String a : List.of("EMP001", "EMP002", "EMP003", "EMP004")) {
            for (String b : List.of("EMP001", "EMP002", "EMP003", "EMP004")) {
                if (!a.equals(b)) {
                    Set<String> aSees = canariesIn(lastPromptFor(a));
                    Set<String> bOwn = oracle.docs().values().stream().filter(d -> b.equals(d.owner())).map(PermissionsOracle.Doc::code).collect(Collectors.toSet());
                    assertThat(aSees).as(a + " no debe ver documentos propios de " + b).doesNotContainAnyElementsOf(
                            bOwn.stream().filter(c -> !oracle.canRead(a, c)).toList());
                }
            }
        }
    }

    private String lastPromptFor(String user) throws Exception {
        FakeDify.SERVER.resetRequests();
        ChatSupport.Result r = chat.ask(user, "pregunta");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.first("done")).as("evento done de " + user + " en: " + r.raw().replace("\n", "|")).isNotNull();
        return lastPrompt();
    }

    @Test
    void metadataFilterSentToDifyIsDerivedFromTheJwtNotFromTheClient() throws Exception {
        chat.ask("ACC001", "gastos");
        JsonNode acc = FakeDify.requestsTo("/v1/datasets/ds-finanzas/retrieve").get(0).at("/retrieval_model/metadata_filtering_conditions");
        assertThat(acc.get("logical_operator").asText()).isEqualTo("and");
        assertThat(acc.at("/conditions/0/name").asText()).isEqualTo("allowed_roles");
        assertThat(acc.at("/conditions/0/comparison_operator").asText()).isEqualTo("contains");
        assertThat(acc.at("/conditions/0/value").asText()).isEqualTo(",ACCOUNTANT,");
        assertThat(FakeDify.requestsTo("/v1/datasets/ds-direccion/retrieve")).as("ACCOUNTANT no consulta Direccion").isEmpty();

        FakeDify.SERVER.resetRequests();
        chat.ask("EMP002", "mi contrato");
        JsonNode emp = FakeDify.requestsTo("/v1/datasets/ds-empleados/retrieve").get(0).at("/retrieval_model/metadata_filtering_conditions");
        UUID emp2 = jdbc.queryForObject("SELECT id FROM users WHERE employee_code = 'EMP002'", UUID.class);
        assertThat(emp.get("logical_operator").asText()).isEqualTo("or");
        assertThat(emp.at("/conditions/0/value").asText()).isEqualTo(",EMPLOYEE,");
        assertThat(emp.at("/conditions/1/name").asText()).isEqualTo("owner_id");
        assertThat(emp.at("/conditions/1/comparison_operator").asText()).isEqualTo("is");
        assertThat(emp.at("/conditions/1/value").asText()).isEqualTo(emp2.toString());

        FakeDify.SERVER.resetRequests();
        chat.ask("DIR001", "todo");
        assertThat(FakeDify.requestsTo("/v1/datasets/ds-direccion/retrieve").get(0).at("/retrieval_model").has("metadata_filtering_conditions")).isFalse();
    }

    @Test
    void employeeIdRoleAssistantIdAndDatasetIdSentByTheClientAreIgnoredAndAudited() throws Exception {
        UUID emp2 = jdbc.queryForObject("SELECT id FROM users WHERE employee_code = 'EMP002'", UUID.class);
        String body = """
                {"message":"mi contrato","employee_id":"%s","role":"MANAGER","assistantId":"x","datasetId":"ds-direccion","employeeId":"%s"}
                """.formatted(emp2, emp2);
        ChatSupport.Result r = chat.send("EMP001", body);
        assertThat(r.status()).isEqualTo(200);
        assertThat(canariesIn(lastPrompt())).isEqualTo(expectedFor("EMP001"));
        assertThat(FakeDify.requestsTo("/v1/datasets/ds-direccion/retrieve")).as("EMPLOYEE nunca consulta Direccion aunque lo pida").isEmpty();
        List<String> audit = jdbc.queryForList("SELECT details::text FROM audit_logs WHERE action = 'IGNORED_CLIENT_FIELD' AND user_id = "
                + "(SELECT id FROM users WHERE employee_code = 'EMP001')", String.class);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("employee_id", "role", "assistantid", "datasetid").doesNotContain(emp2.toString()).doesNotContain("MANAGER");
    }

    @Test
    void foreignConversationsAre404ForEverythingEvenForTheManager() throws Exception {
        ChatSupport.Result r = chat.ask("EMP001", "hola");
        UUID conv = UUID.fromString(r.first("conversation").get("conversationId").asText());
        for (String other : List.of("EMP002", "DIR001", "ACC001", "COM001")) {
            mvc.perform(get("/api/v1/chat/conversations/" + conv + "/messages").header("Authorization", rest.bearer(other))).andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/chat/conversations/" + conv).header("Authorization", rest.bearer(other))).andExpect(status().isNotFound());
            ChatSupport.Result post = chat.send(other, "{\"message\":\"x\",\"conversationId\":\"" + conv + "\"}");
            assertThat(post.status()).as(other + " no puede continuar la conversación ajena").isEqualTo(404);
            String list = mvc.perform(get("/api/v1/chat/conversations").header("Authorization", rest.bearer(other))).andReturn().getResponse().getContentAsString();
            assertThat(list).doesNotContain(conv.toString());
        }
        // un ID inexistente es indistinguible
        mvc.perform(get("/api/v1/chat/conversations/" + UUID.randomUUID() + "/messages").header("Authorization", rest.bearer("EMP001"))).andExpect(status().isNotFound());
        // el propietario sí: lista, lee, continúa y borra (y borrada ⇒ 404)
        assertThat(mvc.perform(get("/api/v1/chat/conversations").header("Authorization", rest.bearer("EMP001"))).andReturn().getResponse().getContentAsString()).contains(conv.toString());
        assertThat(chat.send("EMP001", "{\"message\":\"otra\",\"conversationId\":\"" + conv + "\"}").status()).isEqualTo(200);
        mvc.perform(delete("/api/v1/chat/conversations/" + conv).header("Authorization", rest.bearer("EMP001"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/chat/conversations/" + conv + "/messages").header("Authorization", rest.bearer("EMP001"))).andExpect(status().isNotFound());
    }

    @Test
    void revokingARoleOrChangingTheOwnerTakesEffectWithoutReindexing() throws Exception {
        assertThat(canariesIn(lastPromptFor("ACC001"))).contains("D07");
        FakeDify.SERVER.resetRequests();
        jdbc.update("UPDATE documents SET allowed_roles = ARRAY['MANAGER'] WHERE code = 'D07'");   // Dify sigue con metadatos viejos
        assertThat(canariesIn(lastPromptFor("ACC001"))).as("NEG-09/10: el índice de Dify es obsoleto pero se re-autoriza").doesNotContain("D07");
        assertThat(FakeDify.requestsTo("/v1/datasets/[^/]+/document/create-by-text")).as("sin reindexar").isEmpty();
        // cambio de propietario: D14 pasa de EMP001 a EMP002
        assertThat(canariesIn(lastPromptFor("EMP001"))).contains("D14");
        jdbc.update("UPDATE documents SET owner_id = (SELECT id FROM users WHERE employee_code = 'EMP002') WHERE code = 'D14'");
        assertThat(canariesIn(lastPromptFor("EMP001"))).doesNotContain("D14");
        assertThat(canariesIn(lastPromptFor("EMP002"))).contains("D14");
        jdbc.update("UPDATE documents SET owner_id = (SELECT id FROM users WHERE employee_code = 'EMP001') WHERE code = 'D14'");
        jdbc.update("UPDATE documents SET allowed_roles = ARRAY['MANAGER','ACCOUNTANT'] WHERE code = 'D07'");
        // una conversación ya guardada deja de mostrar la cita revocada al releerla
    }

    @Test
    void staleOrInconsistentIndexEntriesAreDropped() throws Exception {
        Doc d07 = docs().stream().filter(d -> d.code().equals("D07")).findFirst().orElseThrow();
        Doc d04 = docs().stream().filter(d -> d.code().equals("D04")).findFirst().orElseThrow();
        Doc d08 = docs().stream().filter(d -> d.code().equals("D08")).findFirst().orElseThrow();
        // (a) documento de Dify que no existe en nuestra BD; (b) doc_id del metadato que no coincide con el documento mapeado
        FakeDify.stubRetrieve("ds-finanzas", List.of(
                new FakeDify.Frag(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "seg-x", "CANARY_FANTASMA contenido", 0.9),
                new FakeDify.Frag(d08.difyId(), d04.id().toString(), "seg-y", "CANARY_D08 contenido inconsistente", 0.8),
                new FakeDify.Frag(d07.difyId(), d07.id().toString(), "seg-z", "CANARY_D07 contenido legitimo", 0.7)));
        chat.ask("ACC001", "gastos");
        String prompt = lastPrompt();
        assertThat(prompt).doesNotContain("FANTASMA").doesNotContain("inconsistente").contains("legitimo");
        Integer dropped = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'FRAGMENT_DROPPED' AND reason_code IN ('UNMAPPED','METADATA_MISMATCH')", Integer.class);
        assertThat(dropped).isGreaterThanOrEqualTo(1);
    }

    @Test
    void completedMessagesStoreTokensLatencySourcesAndVerification() throws Exception {
        FakeDify.stubGeneration("Según el procedimiento [D07] hay 1.234,50 € (no consta en el contexto).", 100, 20);
        ChatSupport.Result r = chat.ask("ACC001", "¿gastos?");
        JsonNode done = r.first("done");
        assertThat(done.get("tokensIn").asInt()).as("plan 50 + generación 100").isEqualTo(150);
        assertThat(done.get("tokensOut").asInt()).isEqualTo(30);
        JsonNode verification = r.first("verification");
        assertThat(verification.get("citedCodes").toString()).contains("D07");
        assertThat(verification.get("citationsValid").asBoolean()).isTrue();
        assertThat(verification.get("unsupportedNumbers").toString()).contains("1.234,50");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, tokens_in, tokens_out, latency_ms, content, verification::text AS v FROM messages WHERE role = 'ASSISTANT'");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("tokens_in")).isEqualTo(150);
        assertThat(row.get("tokens_out")).isEqualTo(30);
        assertThat((Integer) row.get("latency_ms")).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(row.get("content").toString()).startsWith("Según el procedimiento [D07]");
        assertThat(row.get("v").toString()).contains("citedCodes");
        // un código citado que no está entre los fragmentos autorizados invalida la verificación (señal, no bloqueo)
        FakeDify.stubGeneration("Mira [D04] y [D07].", 10, 5);
        JsonNode v2 = chat.ask("ACC001", "¿plan?").first("verification");
        assertThat(v2.get("citationsValid").asBoolean()).isFalse();
        assertThat(v2.get("unknownCitations").toString()).contains("D04");
    }

    @Test
    void interruptedStreamEndsAsFailedKeepingThePartialTextAndSendsAnErrorEvent() throws Exception {
        FakeDify.stubInterruptedGeneration("Texto parcial");
        ChatSupport.Result r = chat.ask("ACC001", "gastos");
        assertThat(r.first("error")).isNotNull();
        assertThat(r.first("error").get("code").asText()).isEqualTo("ai-unavailable");
        assertThat(r.first("done")).isNull();
        assertThat(r.raw()).doesNotContain("Texto parcial".toUpperCase()).doesNotContain("http");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, content FROM messages WHERE role = 'ASSISTANT'");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("content")).isEqualTo("Texto parcial");
        // la conversación sigue utilizable (no se bloquea)
        FakeDify.stubGeneration("Ya funciona.", 10, 5);
        assertThat(chat.ask("ACC001", "otra vez").first("done")).isNotNull();
    }

    @Test
    void aTransientDifyFailureBeforeTheFirstTokenIsRetriedOnce() throws Exception {
        String scenario = "retry";
        FakeDify.SERVER.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/chat-messages")).withHeader("Authorization", WireMock.equalTo("Bearer " + FakeDify.APP_KEY))
                .inScenario(scenario).whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(WireMock.aResponse().withStatus(503)).willSetStateTo("second"));
        FakeDify.SERVER.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/chat-messages")).withHeader("Authorization", WireMock.equalTo("Bearer " + FakeDify.APP_KEY))
                .inScenario(scenario).whenScenarioStateIs("second")
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"event\":\"message\",\"answer\":\"Tras el reintento\"}\n\ndata: {\"event\":\"message_end\",\"metadata\":{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}}\n\n")));
        ChatSupport.Result r = chat.ask("ACC001", "gastos");
        assertThat(r.text()).isEqualTo("Tras el reintento");
        assertThat(FakeDify.chatRequests(FakeDify.APP_KEY)).hasSize(2);
    }

    @Test
    void difyDownIsAGracefulErrorEventAndNeverLeaksDetails() throws Exception {
        FakeDify.stubGenerationStatus(500);
        ChatSupport.Result r = chat.ask("ACC001", "gastos");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.first("error").get("status").asInt()).isEqualTo(503);
        assertThat(r.raw()).doesNotContain(FakeDify.APP_KEY).doesNotContain(FakeDify.KNOWLEDGE_KEY).doesNotContain("Dify");
        assertThat(jdbc.queryForObject("SELECT status FROM messages WHERE role = 'ASSISTANT'", String.class)).isEqualTo("FAILED");
    }

    @Test
    void validationAndAuthentication() throws Exception {
        assertThat(chat.send(null, "{\"message\":\"x\"}").status()).isEqualTo(401);
        assertThat(chat.send("EMP001", "{\"message\":\"  \"}").status()).isEqualTo(400);
        assertThat(chat.send("EMP001", "{}").status()).isEqualTo(400);
        assertThat(chat.send("EMP001", "{\"message\":\"" + "x".repeat(2001) + "\"}").status()).isEqualTo(400);
    }

    @Test
    void noContentOrSecretsReachTheLogsOrTheAuditTrail() throws Exception {
        chat.ask("EMP001", "PREGUNTA_SECRETA_XYZ");
        String audit = jdbc.queryForList("SELECT details::text FROM audit_logs", String.class).toString()
                + jdbc.queryForList("SELECT coalesce(resource_id,'') || coalesce(reason_code,'') FROM audit_logs", String.class);
        assertThat(audit).doesNotContain("PREGUNTA_SECRETA_XYZ").doesNotContain("CANARY_").doesNotContain(FakeDify.APP_KEY);
    }
}
