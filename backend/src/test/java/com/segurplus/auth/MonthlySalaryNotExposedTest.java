package com.segurplus.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.segurplus.support.IntegrationTest;
import com.segurplus.support.RestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

/** F2 criterio 6 (ampliado en F3 a `/dashboard`): {@code users.monthly_salary} no aparece en ningún DTO, endpoint ni en la documentación OpenAPI. */
class MonthlySalaryNotExposedTest extends IntegrationTest {

    private static final List<String> USERS = List.of("DIR001", "ACC001", "COM001", "EMP001", "EMP002", "EMP003", "EMP004");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void openApiDocumentNeverMentionsSalary() throws Exception {
        String api = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        assertThat(api).isNotBlank().doesNotContainIgnoringCase("salary").doesNotContainIgnoringCase("salario");
    }

    @Test
    void realResponsesNeverContainSalaryForAnyUser() throws Exception {
        RestSupport rest = new RestSupport(mvc, mapper);
        for (String user : USERS) {
            String auth = rest.bearer(user);
            List<String> bodies = new java.util.ArrayList<>();
            bodies.add(mvc.perform(get("/api/v1/users/me").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
            bodies.add(mvc.perform(get("/api/v1/dashboard").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
            bodies.add(mvc.perform(get("/api/v1/documents?size=50").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
            for (UUID id : jdbc.queryForList("SELECT id FROM documents", UUID.class)) {
                bodies.add(mvc.perform(get("/api/v1/documents/" + id).header("Authorization", auth)).andReturn().getResponse().getContentAsString());
            }
            for (String body : bodies) {
                assertThat(body).doesNotContainIgnoringCase("salary").doesNotContainIgnoringCase("salario");
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE monthly_salary > 0", Integer.class)).isEqualTo(7);   // existe en BD, pero no sale
    }
}
