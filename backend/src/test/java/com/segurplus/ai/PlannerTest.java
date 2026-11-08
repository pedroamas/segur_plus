package com.segurplus.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** El plan lo propone un LLM: el backend valida todo contra el catálogo del rol y descarta lo demás (ADR 0005). */
class PlannerTest {

    private final Planner planner = new Planner(null, new ObjectMapper(), Clock.systemUTC(), null);
    private final AiProperties.Profile employee = new AiProperties.Profile(List.of("GENERAL", "EMPLEADOS"),
            List.of("get_sales_summary", "get_commissions", "get_policies_by_status", "get_targets", "search_documents"), false, true);
    private final AiProperties.Profile accountant = new AiProperties.Profile(List.of("GENERAL", "FINANZAS"),
            List.of("get_sales_summary", "get_commissions", "get_policies_by_status", "search_documents"), false, false);

    private Plan parse(String raw, AiProperties.Profile p) {
        return planner.parse(raw, p, "pregunta original", AiGateway.Usage.NONE).plan();
    }

    @Test
    void validToolsAreKeptWithOnlyTheAllowedParameters() {
        Plan plan = parse("{\"tools\":[{\"name\":\"get_policies_by_status\",\"params\":{\"period\":\"MONTH:2026-10\",\"status\":\"cancelled\",\"employee_id\":\"x\"}}]}", employee);
        assertThat(plan.tools()).hasSize(1);
        assertThat(plan.tools().get(0).params()).containsOnlyKeys("period", "status").containsEntry("status", "CANCELLED");
        assertThat(plan.retrieveQuery()).as("solo herramientas: no se busca en documentos").isNull();
    }

    @Test
    void statusIsOnlyAcceptedWhereItExistsAndWithValidValues() {
        Plan plan = parse("{\"tools\":[{\"name\":\"get_sales_summary\",\"params\":{\"status\":\"ACTIVE\"}},{\"name\":\"get_policies_by_status\",\"params\":{\"status\":\"DROP TABLE\"}}]}", employee);
        assertThat(plan.tools().get(0).params()).doesNotContainKey("status");
        assertThat(plan.tools().get(1).params()).doesNotContainKey("status");
    }

    @Test
    void toolsOutsideTheRoleCatalogAreDropped() {
        Plan plan = parse("{\"tools\":[{\"name\":\"get_targets\",\"params\":{}},{\"name\":\"run_sql\",\"params\":{\"q\":\"select 1\"}},{\"name\":\"get_commissions\",\"params\":{}}]}", accountant);
        assertThat(plan.tools()).extracting(ToolCall::name).containsExactly("get_commissions");
    }

    @Test
    void atMostFourToolCalls() {
        String one = "{\"name\":\"get_sales_summary\",\"params\":{}}";
        Plan plan = parse("{\"tools\":[" + String.join(",", java.util.Collections.nCopies(7, one)) + "]}", employee);
        assertThat(plan.tools()).hasSize(4);
    }

    @Test
    void retrieveVariantsAreUnderstoodAndTheQueryDefaultsToTheQuestion() {
        assertThat(parse("{\"retrieve\":{\"query\":\"plan estratégico\"},\"tools\":[]}", employee).retrieveQuery()).isEqualTo("plan estratégico");
        assertThat(parse("{\"retrieve\":true}", employee).retrieveQuery()).isEqualTo("pregunta original");
        assertThat(parse("{\"tools\":[{\"name\":\"search_documents\",\"params\":{\"query\":\"hogar\"}}]}", employee).retrieveQuery()).isEqualTo("hogar");
    }

    @Test
    void garbageOrEmptyPlansFallBackToDocumentSearch() {
        assertThat(parse("no soy json", employee).retrieveQuery()).isEqualTo("pregunta original");
        assertThat(parse("{}", employee).retrieveQuery()).isEqualTo("pregunta original");
        assertThat(parse("```json\n{\"tools\":[{\"name\":\"get_targets\",\"params\":{}}]}\n```", employee).tools()).hasSize(1);
    }

    @Test
    void searchDocumentsIsIgnoredIfTheRoleDoesNotHaveIt() {
        AiProperties.Profile none = new AiProperties.Profile(List.of("GENERAL"), List.of("get_sales_summary"), false, false);
        Plan plan = parse("{\"tools\":[{\"name\":\"search_documents\",\"params\":{\"query\":\"x\"}}]}", none);
        assertThat(plan.tools()).isEmpty();
    }
}
