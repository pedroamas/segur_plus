package com.segurplus.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Login real por REST y caché de access tokens por usuario. */
public final class RestSupport {

    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final Map<String, String> tokens = new HashMap<>();

    public RestSupport(MockMvc mvc, ObjectMapper mapper) {
        this.mvc = mvc;
        this.mapper = mapper;
    }

    public String token(String employeeCode) throws Exception {
        String cached = tokens.get(employeeCode);
        if (cached != null) {
            return cached;
        }
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(employeeCode, IntegrationTest.SEED_PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).get("accessToken").asText();
        tokens.put(employeeCode, token);
        return token;
    }

    public String bearer(String employeeCode) throws Exception {
        return "Bearer " + token(employeeCode);
    }
}
