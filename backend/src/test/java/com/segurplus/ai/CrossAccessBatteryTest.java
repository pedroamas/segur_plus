package com.segurplus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.AiIntegrationTest;
import com.segurplus.support.ChatSupport;
import com.segurplus.support.FakeDify;
import com.segurplus.support.OctoberDataset;
import com.segurplus.support.PermissionsOracle;
import com.segurplus.support.RestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F6, criterio 1: BATERÍA COMPLETA DE ACCESO CRUZADO por todos los canales para los 7 usuarios del seed
 * (DIR001, ACC001, COM001, EMP001-EMP004). Cada comprobación se cuenta por canal; al final la suite exige 0 fallos y deja
 * el informe en {@code target/security-reports/cross-access.md}. Canales: REST (detalle, descarga, visor, preview, listado,
 * IDs alterados, escritura, usuarios, dashboard), RAG (lo que ve el LLM), citas (las que recibe el cliente, las guardadas y
 * las que inventa un modelo), herramientas (alcance por rol; sin salarios) y conversaciones ajenas (42 pares).
 * Dify va SIMULADO y devuelve el corpus ENTERO ignorando los filtros (el peor caso): la defensa es la re-autorización.
 * El oráculo es independiente de la aplicación (permissions-spec.yaml).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossAccessBatteryTest extends AiIntegrationTest {

    private static final Map<String, Set<String>> KBS = Map.of(
            "MANAGER", Set.of("GENERAL", "DIRECCION", "FINANZAS", "EMPLEADOS"),
            "ACCOUNTANT", Set.of("GENERAL", "FINANZAS"),
            "SALES_MANAGER", Set.of("GENERAL", "DIRECCION", "FINANZAS", "EMPLEADOS"),
            "EMPLOYEE", Set.of("GENERAL", "EMPLEADOS"));
    private static final Pattern TOOL_BLOCK = Pattern.compile("<<<HERRAMIENTA (\\w+) [0-9a-f]{8}>>>\\n(.*?)\\n<<<FIN", Pattern.DOTALL);

    /** Contadores por canal: comprobaciones hechas y fallos (con descripción). */
    static final class Battery {
        final Map<String, Integer> checks = new TreeMap<>();
        final Map<String, List<String>> failures = new TreeMap<>();

        synchronized void check(String channel, boolean ok, String what) {
            checks.merge(channel, 1, Integer::sum);
            if (!ok) {
                failures.computeIfAbsent(channel, k -> new ArrayList<>()).add(what);
            }
        }

        int total() {
            return checks.values().stream().mapToInt(Integer::intValue).sum();
        }

        int failed() {
            return failures.values().stream().mapToInt(List::size).sum();
        }
    }

    static final Battery BATTERY = new Battery();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentIndexer indexer;

    private final PermissionsOracle oracle = new PermissionsOracle();
    private RestSupport rest;
    private ChatSupport chat;
    private Map<String, UUID> idByCode;
    private Map<UUID, String> codeById;
    private Map<String, String> kbByCode;
    private Map<String, String> difyIdByCode;
    private OctoberDataset october;

    @BeforeEach
    void setUp() {
        rest = new RestSupport(mvc, mapper);
        chat = new ChatSupport(port, mapper, rest);
        october = new OctoberDataset(jdbc);
        october.load();
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM conversations");
        FakeDify.reset();
        jdbc.update("UPDATE documents SET index_status = 'PENDING', dify_dataset_id = NULL, dify_document_id = NULL, indexed_at = NULL");
        indexer.runOnce();
        idByCode = new LinkedHashMap<>();
        codeById = new LinkedHashMap<>();
        kbByCode = new LinkedHashMap<>();
        difyIdByCode = new LinkedHashMap<>();
        jdbc.query("SELECT id, code, kb, dify_document_id FROM documents WHERE deleted_at IS NULL AND code NOT LIKE 'ZZ%' ORDER BY code", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            idByCode.put(rs.getString("code"), id);
            codeById.put(id, rs.getString("code"));
            kbByCode.put(rs.getString("code"), rs.getString("kb"));
            difyIdByCode.put(rs.getString("code"), rs.getString("dify_document_id"));
        });
        stubFullCorpus();
    }

    @AfterEach
    void tearDown() {
        october.clean();
    }

    /** Cada dataset devuelve TODOS sus documentos con un canario, sin mirar ningún filtro. */
    private void stubFullCorpus() {
        FakeDify.DATASETS.forEach((kb, ds) -> {
            List<FakeDify.Frag> frags = new ArrayList<>();
            double score = 0.99;
            for (String code : idByCode.keySet()) {
                if (kbByCode.get(code).equals(kb)) {
                    frags.add(new FakeDify.Frag(difyIdByCode.get(code), idByCode.get(code).toString(), "seg-" + code,
                            "CANARY_" + code + " contenido del documento " + code, score));
                    score -= 0.01;
                }
            }
            FakeDify.stubRetrieve(ds, frags);
        });
    }

    private Set<String> canariesIn(String text) {
        return idByCode.keySet().stream().filter(c -> text.contains("CANARY_" + c + " ")).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String lastPrompt() {
        List<JsonNode> reqs = FakeDify.chatRequests(FakeDify.APP_KEY);
        assertThat(reqs).isNotEmpty();
        return reqs.get(reqs.size() - 1).get("query").asText();
    }

    private Set<String> expectedInChat(String user) {
        Set<String> out = new LinkedHashSet<>(oracle.readable(user));
        out.removeIf(c -> !KBS.get(oracle.roleOf(user)).contains(kbByCode.get(c)));
        return out;
    }

    // ─────────────────────────── REST ───────────────────────────

    @Test
    @Order(1)
    void restDetailDownloadViewPreviewAndListingForEveryUserAndDocument() throws Exception {
        for (String user : oracle.users()) {
            String auth = rest.bearer(user);
            for (String code : oracle.docs().keySet()) {
                boolean allowed = oracle.canRead(user, code);
                UUID id = idByCode.get(code);
                int detail = mvc.perform(get("/api/v1/documents/" + id).header("Authorization", auth)).andReturn().getResponse().getStatus();
                int download = mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", auth)).andReturn().getResponse().getStatus();
                int view = mvc.perform(get("/api/v1/documents/" + id + "/view").header("Authorization", auth)).andReturn().getResponse().getStatus();
                int preview = mvc.perform(get("/api/v1/documents/" + id + "/preview").header("Authorization", auth)).andReturn().getResponse().getStatus();
                BATTERY.check("REST detalle", allowed ? detail == 200 : detail == 404, user + " x " + code + " -> " + detail);
                BATTERY.check("REST descarga", allowed ? download == 200 : download == 404, user + " x " + code + " -> " + download);
                // visor y preview solo existen para PDF y XLSX: denegado ⇒ SIEMPRE 404; permitido ⇒ nunca 404 por autorización (200 o 4xx por tipo)
                BATTERY.check("REST visor", allowed ? view != 401 && view != 403 : view == 404, user + " x " + code + " view -> " + view);
                BATTERY.check("REST preview", allowed ? preview != 401 && preview != 403 : preview == 404, user + " x " + code + " preview -> " + preview);
            }
            Set<String> seen = new LinkedHashSet<>();
            long total = -1;
            for (int page = 0; page < 5; page++) {
                JsonNode body = mapper.readTree(mvc.perform(get("/api/v1/documents?size=10&page=" + page).header("Authorization", auth))
                        .andReturn().getResponse().getContentAsString());
                total = body.get("totalElements").asLong();
                body.get("items").forEach(i -> seen.add(i.get("code").asText()));
            }
            BATTERY.check("REST listado", seen.equals(oracle.readable(user)) && total == oracle.readable(user).size(),
                    user + " lista=" + seen.size() + " total=" + total + " esperado=" + oracle.readable(user).size());
        }
    }

    @Test
    @Order(2)
    void alteredAndForeignIdsAreIndistinguishableFromNonexistentOnes() throws Exception {
        for (String user : oracle.users()) {
            String auth = rest.bearer(user);
            JsonNode ghost = mapper.readTree(mvc.perform(get("/api/v1/documents/" + UUID.randomUUID()).header("Authorization", auth)).andReturn().getResponse().getContentAsString());
            for (String code : oracle.docs().keySet()) {
                if (oracle.canRead(user, code)) {
                    continue;
                }
                UUID id = idByCode.get(code);
                for (String suffix : List.of("", "/download", "/view", "/preview")) {
                    var res = mvc.perform(get("/api/v1/documents/" + id + suffix).header("Authorization", auth)).andReturn().getResponse();
                    JsonNode denied = mapper.readTree(res.getContentAsString());
                    boolean same = res.getStatus() == 404 && denied.get("type").equals(ghost.get("type")) && denied.get("title").equals(ghost.get("title"))
                            && denied.get("detail").equals(ghost.get("detail")) && !res.getContentAsString().contains(code);
                    BATTERY.check("REST ID ajeno = inexistente", same, user + " x " + code + suffix);
                }
            }
            // IDs mal formados o con el UUID alterado: nunca 200 ni 5xx
            for (String bad : List.of("not-a-uuid", "00000000-0000-0000-0000-000000000000", "../../etc/passwd", "1%27%20OR%20%271%27=%271")) {
                int status = mvc.perform(get("/api/v1/documents/" + bad).header("Authorization", auth)).andReturn().getResponse().getStatus();
                BATTERY.check("REST ID alterado", status == 404 || status == 400, user + " " + bad + " -> " + status);
            }
        }
    }

    @Test
    @Order(3)
    void writeAndAdminEndpointsAreManagerOnlyAndIdentityParametersAreIgnored() throws Exception {
        for (String user : oracle.users()) {
            String auth = rest.bearer(user);
            boolean manager = oracle.roleOf(user).equals("MANAGER");
            int users = mvc.perform(get("/api/v1/users").header("Authorization", auth)).andReturn().getResponse().getStatus();
            int cfg = mvc.perform(get("/api/v1/documents/upload-config").header("Authorization", auth)).andReturn().getResponse().getStatus();
            int upload = mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes()))
                    .param("code", "ZZ99").param("title", "t").param("kb", "GENERAL").param("category", "MANUAL")
                    .param("classification", "INTERNAL").header("Authorization", auth)).andReturn().getResponse().getStatus();
            BATTERY.check("REST escritura/admin", manager ? users == 200 && cfg == 200 : users == 403 && cfg == 403 && upload == 403,
                    user + " users=" + users + " config=" + cfg + " upload=" + upload);
            if (manager) {
                BATTERY.check("REST escritura/admin", upload != 401 && upload != 403, "MANAGER upload=" + upload);
            }
            // el cuerpo de /users (solo MANAGER) y /users/me nunca lleva salario ni contraseña
            String me = mvc.perform(get("/api/v1/users/me").header("Authorization", auth)).andReturn().getResponse().getContentAsString().toLowerCase();
            BATTERY.check("REST sin salario", !me.contains("salary") && !me.contains("password") && !me.contains("hash"), user + " /users/me");
            if (manager) {
                String list = mvc.perform(get("/api/v1/users").header("Authorization", auth)).andReturn().getResponse().getContentAsString().toLowerCase();
                BATTERY.check("REST sin salario", !list.contains("salary") && !list.contains("password"), "/users");
            }
            // identidad del cliente ignorada: con employee_id/role ajenos la respuesta es idéntica a sin ellos
            for (String other : oracle.users()) {
                if (other.equals(user)) {
                    continue;
                }
                String plain = mvc.perform(get("/api/v1/dashboard?month=2026-10").header("Authorization", auth)).andReturn().getResponse().getContentAsString();
                String forged = mvc.perform(get("/api/v1/dashboard?month=2026-10&employee_id=" + other + "&role=MANAGER&employeeCode=" + other)
                        .header("Authorization", auth)).andReturn().getResponse().getContentAsString();
                BATTERY.check("REST identidad del cliente", plain.equals(forged), user + " suplantando a " + other);
            }
        }
        jdbc.update("DELETE FROM documents WHERE code LIKE 'ZZ%'");
    }

    @Test
    @Order(4)
    void dashboardScopeFollowsTheRoleOfTheToken() throws Exception {
        for (String user : oracle.users()) {
            String body = mvc.perform(get("/api/v1/dashboard?month=2026-10").header("Authorization", rest.bearer(user))).andReturn().getResponse().getContentAsString();
            JsonNode d = mapper.readTree(body);
            String premiums = d.at("/sales/premiums/amount").asText();
            String expected = switch (oracle.roleOf(user)) {
                case "EMPLOYEE" -> Map.of("EMP001", "3150.00", "EMP002", "2280.00", "EMP003", "2050.00", "EMP004", "2200.00").get(user);
                default -> "9680.00";
            };
            BATTERY.check("REST dashboard por rol", expected.equals(premiums), user + " primas=" + premiums + " esperado=" + expected);
            BATTERY.check("REST sin salario", !body.toLowerCase().contains("salary"), user + " dashboard");
            if (oracle.roleOf(user).equals("EMPLOYEE")) {
                for (String other : OctoberDataset.EMPLOYEES) {
                    if (!other.equals(user)) {
                        BATTERY.check("REST dashboard por rol", !body.contains(other), user + " no debe mencionar a " + other);
                    }
                }
            }
        }
    }

    // ─────────────────────────── RAG, citas ───────────────────────────

    @Test
    @Order(5)
    void ragPromptAndCitationsContainExactlyWhatTheOracleAllows() throws Exception {
        for (String user : oracle.users()) {
            FakeDify.SERVER.resetRequests();
            // el «modelo» comprometido cita TODOS los documentos del corpus, incluidos los prohibidos
            String citeAll = idByCode.keySet().stream().map(c -> "[" + c + "]").collect(Collectors.joining(" "));
            FakeDify.stubGeneration("Resumen: " + citeAll, 100, 20);
            ChatSupport.Result r = chat.ask(user, "¿Qué dicen los documentos?");
            BATTERY.check("RAG chat", r.status() == 200 && r.first("done") != null, user + " status=" + r.status());
            Set<String> expected = expectedInChat(user);
            BATTERY.check("RAG fragmentos al LLM", canariesIn(lastPrompt()).equals(expected), user + " prompt=" + canariesIn(lastPrompt()));
            // ningún dataset fuera del perfil del rol se consulta siquiera
            for (var e : FakeDify.DATASETS.entrySet()) {
                boolean queried = !FakeDify.requestsTo("/v1/datasets/" + e.getValue() + "/retrieve").isEmpty();
                BATTERY.check("RAG KB por rol", queried == KBS.get(oracle.roleOf(user)).contains(e.getKey()), user + " consultó " + e.getKey());
            }
            Set<String> sources = new LinkedHashSet<>();
            r.first("sources").get("sources").forEach(s -> sources.add(s.get("code").asText()));
            BATTERY.check("Citas (fuentes al cliente)", sources.equals(expected), user + " fuentes=" + sources);
            // las citas inventadas por el modelo que no están autorizadas se señalan como desconocidas, nunca se convierten en fuente
            JsonNode v = r.first("verification");
            Set<String> unknown = new LinkedHashSet<>();
            v.get("unknownCitations").forEach(n -> unknown.add(n.asText()));
            Set<String> expectedUnknown = new LinkedHashSet<>(idByCode.keySet());
            expectedUnknown.removeAll(expected);
            BATTERY.check("Citas (verificación)", unknown.equals(expectedUnknown), user + " desconocidas=" + unknown.size() + " esperado=" + expectedUnknown.size());
            UUID conv = UUID.fromString(r.first("conversation").get("conversationId").asText());
            JsonNode msgs = mapper.readTree(mvc.perform(get("/api/v1/chat/conversations/" + conv + "/messages").header("Authorization", rest.bearer(user)))
                    .andReturn().getResponse().getContentAsString());
            Set<String> persisted = new LinkedHashSet<>();
            msgs.forEach(m -> m.get("sources").forEach(s -> persisted.add(s.get("code").asText())));
            BATTERY.check("Citas (guardadas)", persisted.equals(expected), user + " guardadas=" + persisted.size());
        }
    }

    @Test
    @Order(6)
    void revokedPermissionsDisappearFromEveryChannelWithoutReindexing() throws Exception {
        // ACC001 pierde D07; EMP001 pierde D14 (propietario nuevo = EMP002): REST, chat y listados lo reflejan al instante
        jdbc.update("UPDATE documents SET allowed_roles = ARRAY['MANAGER'] WHERE code = 'D07'");
        jdbc.update("UPDATE documents SET owner_id = (SELECT id FROM users WHERE employee_code = 'EMP002') WHERE code = 'D14'");
        try {
            int d07 = mvc.perform(get("/api/v1/documents/" + idByCode.get("D07")).header("Authorization", rest.bearer("ACC001"))).andReturn().getResponse().getStatus();
            int d14 = mvc.perform(get("/api/v1/documents/" + idByCode.get("D14") + "/download").header("Authorization", rest.bearer("EMP001"))).andReturn().getResponse().getStatus();
            BATTERY.check("Revocación", d07 == 404 && d14 == 404, "REST d07=" + d07 + " d14=" + d14);
            FakeDify.SERVER.resetRequests();
            chat.ask("ACC001", "gastos");
            BATTERY.check("Revocación", !canariesIn(lastPrompt()).contains("D07"), "RAG D07 para ACC001");
            chat.ask("EMP001", "mi contrato");
            BATTERY.check("Revocación", !canariesIn(lastPrompt()).contains("D14"), "RAG D14 para EMP001");
            chat.ask("EMP002", "mi contrato");
            BATTERY.check("Revocación", canariesIn(lastPrompt()).contains("D14"), "D14 pasa a EMP002");
        } finally {
            jdbc.update("UPDATE documents SET allowed_roles = ARRAY['MANAGER','ACCOUNTANT'] WHERE code = 'D07'");
            jdbc.update("UPDATE documents SET owner_id = (SELECT id FROM users WHERE employee_code = 'EMP001') WHERE code = 'D14'");
        }
    }

    // ─────────────────────────── herramientas ───────────────────────────

    @Test
    @Order(7)
    void toolsReturnOnlyTheScopeOfTheRoleAndNeverSalaries() throws Exception {
        FakeDify.stubPlan("""
                {"retrieve":null,"tools":[{"name":"get_sales_summary","params":{"period":"MONTH:2026-10"}},{"name":"get_commissions","params":{"period":"MONTH:2026-10"}},
                 {"name":"get_targets","params":{"period":"MONTH:2026-10"}},{"name":"get_policies_by_status","params":{"period":"MONTH:2026-10"}},
                 {"name":"get_salary","params":{}},{"name":"run_sql","params":{"query":"select monthly_salary from users"}}]}""");
        Map<String, String> premiums = Map.of("EMP001", "3150.00", "EMP002", "2280.00", "EMP003", "2050.00", "EMP004", "2200.00");
        Map<String, String> accrued = Map.of("EMP001", "315.00", "EMP002", "228.00", "EMP003", "205.00", "EMP004", "220.00");
        for (String user : oracle.users()) {
            FakeDify.SERVER.resetRequests();
            ChatSupport.Result r = chat.ask(user, "cifras de octubre");
            BATTERY.check("Herramientas", r.status() == 200 && r.first("done") != null, user + " chat");
            String prompt = lastPrompt();
            Map<String, JsonNode> t = new LinkedHashMap<>();
            Matcher m = TOOL_BLOCK.matcher(prompt);
            while (m.find()) {
                t.put(m.group(1), mapper.readTree(m.group(2)));
            }
            String role = oracle.roleOf(user);
            Set<String> expectedTools = role.equals("ACCOUNTANT") ? Set.of("get_sales_summary", "get_commissions", "get_policies_by_status")
                    : Set.of("get_sales_summary", "get_commissions", "get_targets", "get_policies_by_status");
            BATTERY.check("Herramientas", t.keySet().equals(expectedTools), user + " herramientas=" + t.keySet() + " (get_salary y run_sql no existen)");
            String expectedPremiums = role.equals("EMPLOYEE") ? premiums.get(user) : "9680.00";
            BATTERY.check("Herramientas", expectedPremiums.equals(t.get("get_sales_summary").at("/totals/premiums").asText()),
                    user + " primas=" + t.get("get_sales_summary").at("/totals/premiums").asText());
            String expectedAccrued = role.equals("EMPLOYEE") ? accrued.get(user) : "968.00";
            BATTERY.check("Herramientas", expectedAccrued.equals(t.get("get_commissions").at("/totals/accrued").asText()), user + " comisiones");
            if (role.equals("EMPLOYEE")) {
                BATTERY.check("Herramientas", t.get("get_targets").at("/rows").size() == 1
                        && user.equals(t.get("get_targets").at("/rows/0/employee").asText()), user + " objetivos propios");
                for (String other : OctoberDataset.EMPLOYEES) {
                    if (!other.equals(user)) {
                        BATTERY.check("Herramientas", !prompt.contains("\"" + other + "\"") && !prompt.contains(premiums.get(other)), user + " no ve a " + other);
                    }
                }
            }
            if (role.equals("ACCOUNTANT")) {
                BATTERY.check("Herramientas", !prompt.contains("EMP00"), "ACC001 solo agregados");
            }
            BATTERY.check("Herramientas sin salario", !prompt.toLowerCase().contains("salary") && !prompt.contains("monthly_salary"), user + " sin salario");
        }
    }

    // ─────────────────────────── conversaciones ajenas ───────────────────────────

    @Test
    @Order(8)
    void conversationsAreOnlyVisibleToTheirOwnerForEveryPairOfUsers() throws Exception {
        Map<String, UUID> conv = new LinkedHashMap<>();
        for (String user : oracle.users()) {
            ChatSupport.Result r = chat.ask(user, "Pregunta privada de " + user);
            conv.put(user, UUID.fromString(r.first("conversation").get("conversationId").asText()));
        }
        for (String owner : oracle.users()) {
            UUID id = conv.get(owner);
            for (String other : oracle.users()) {
                if (other.equals(owner)) {
                    continue;
                }
                String auth = rest.bearer(other);
                int read = mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages").header("Authorization", auth)).andReturn().getResponse().getStatus();
                int del = mvc.perform(delete("/api/v1/chat/conversations/" + id).header("Authorization", auth)).andReturn().getResponse().getStatus();
                int cont = chat.send(other, "{\"message\":\"x\",\"conversationId\":\"" + id + "\"}").status();
                String list = mvc.perform(get("/api/v1/chat/conversations").header("Authorization", auth)).andReturn().getResponse().getContentAsString();
                BATTERY.check("Conversaciones ajenas", read == 404 && del == 404 && cont == 404 && !list.contains(id.toString()) && !list.contains("Pregunta privada de " + owner),
                        other + " -> conversación de " + owner + " read=" + read + " del=" + del + " cont=" + cont);
            }
            int own = mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages").header("Authorization", rest.bearer(owner))).andReturn().getResponse().getStatus();
            BATTERY.check("Conversaciones propias (control positivo)", own == 200, owner + " lee la suya -> " + own);
        }
        int still = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE deleted_at IS NULL", Integer.class);
        BATTERY.check("Conversaciones ajenas", still == oracle.users().size(), "ninguna conversación se borró por el DELETE de otro usuario: " + still);
    }

    // ─────────────────────────── informe ───────────────────────────

    @Test
    @Order(99)
    void zeroCrossAccessesInTheWholeBattery() {
        BATTERY.failures.forEach((channel, list) -> list.stream().limit(5).forEach(f -> System.out.println("FALLO [" + channel + "] " + f)));
        System.out.printf("CROSS-ACCESS BATTERY checks=%d failures=%d channels=%d%n", BATTERY.total(), BATTERY.failed(), BATTERY.checks.size());
        assertThat(BATTERY.failures).as("accesos cruzados / fallos por canal").isEmpty();
        assertThat(BATTERY.checks.keySet()).contains("REST detalle", "REST descarga", "REST listado", "RAG fragmentos al LLM",
                "Citas (fuentes al cliente)", "Herramientas", "Conversaciones ajenas");
        assertThat(BATTERY.total()).isGreaterThan(1000);
    }

    @AfterAll
    static void writeReport() throws Exception {
        StringBuilder sb = new StringBuilder("# Batería de acceso cruzado (F6)\n\n| Canal | Comprobaciones | Fallos |\n|---|---:|---:|\n");
        BATTERY.checks.forEach((c, n) -> sb.append("| ").append(c).append(" | ").append(n).append(" | ")
                .append(BATTERY.failures.getOrDefault(c, List.of()).size()).append(" |\n"));
        sb.append("| **Total** | **").append(BATTERY.total()).append("** | **").append(BATTERY.failed()).append("** |\n");
        Path out = Path.of("target", "security-reports", "cross-access.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
    }
}
