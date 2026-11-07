package com.segurplus.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import com.segurplus.support.RestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/users: solo MANAGER, solo campos mínimos, nunca salario ni correo. */
class UserDirectoryTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private MockHttpServletResponse call(String user) throws Exception {
        return mvc.perform(get("/api/v1/users").header("Authorization", new RestSupport(mvc, mapper).bearer(user))).andReturn().getResponse();
    }

    @Test
    void managerListsTheSevenActiveUsersWithMinimalFields() throws Exception {
        MockHttpServletResponse r = call("DIR001");
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(body).hasSize(7);
        assertThat(body.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "employeeCode", "fullName", "role");
        assertThat(r.getContentAsString()).doesNotContainIgnoringCase("salary").doesNotContainIgnoringCase("email")
                .doesNotContainIgnoringCase("password");
    }

    @Test
    void otherRolesGet403() throws Exception {
        for (String user : List.of("ACC001", "COM001", "EMP001")) {
            MockHttpServletResponse r = call(user);
            assertThat(r.getStatus()).as(user).isEqualTo(403);
            assertThat(r.getContentType()).startsWith("application/problem+json");
        }
    }

    @Test
    void anonymousGets401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/users")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
