package com.segurplus.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Criterio 5 de F1: rotación, reutilización, logout, caducidad, cookie y ausencia del token en claro en BD. */
class SessionTest extends IntegrationTest {

    private static final String COOKIE = "segurplus_refresh";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    record Login(String access, String refresh, MvcResult result) {
    }

    Login login(String code) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(code, SEED_PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return toLogin(r);
    }

    Login toLogin(MvcResult r) throws Exception {
        JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
        return new Login(body.get("accessToken").asText(), r.getResponse().getCookie(COOKIE).getValue(), r);
    }

    MvcResult refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, token))).andReturn();
    }

    String familyOf(String raw) {
        return jdbc.queryForObject("SELECT family_id::text FROM refresh_tokens WHERE token_hash = ?", String.class, sha256(raw));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void loginSetsHardenedCookieAndShortLivedAccessToken() throws Exception {
        Login l = login("EMP001");
        String setCookie = l.result().getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(COOKIE + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");
        Cookie cookie = l.result().getResponse().getCookie(COOKIE);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        // El refresh no viaja en el cuerpo.
        assertThat(l.result().getResponse().getContentAsString()).doesNotContain(l.refresh());

        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(l.access().split("\\.")[1]));
        long lifetime = payload.get("exp").asLong() - payload.get("iat").asLong();
        assertThat(lifetime).isBetween(600L, 900L);
        assertThat(payload.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(payload.has("monthly_salary")).isFalse();
    }

    @Test
    void refreshRotatesTokenAndLinksReplacedBy() throws Exception {
        Login l = login("EMP002");
        MvcResult second = refresh(l.refresh());
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        Login next = toLogin(second);

        assertThat(next.refresh()).isNotEqualTo(l.refresh());
        assertThat(next.access()).isNotEqualTo(l.access());
        Map<String, Object> old = jdbc.queryForMap("SELECT revoked_at, replaced_by, family_id FROM refresh_tokens WHERE token_hash = ?", sha256(l.refresh()));
        Map<String, Object> fresh = jdbc.queryForMap("SELECT id, revoked_at, family_id FROM refresh_tokens WHERE token_hash = ?", sha256(next.refresh()));
        assertThat(old.get("revoked_at")).isNotNull();
        assertThat(old.get("replaced_by")).isEqualTo(fresh.get("id"));
        assertThat(fresh.get("revoked_at")).isNull();
        assertThat(old.get("family_id")).isEqualTo(fresh.get("family_id"));

        // Cada refresh devuelve un token distinto, también en cadena.
        Login third = toLogin(refresh(next.refresh()));
        assertThat(List.of(l.refresh(), next.refresh(), third.refresh())).doesNotHaveDuplicates();
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + third.access())).andExpect(status().isOk());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamilyIncludingTheNewest() throws Exception {
        Login l = login("EMP003");
        Login second = toLogin(refresh(l.refresh()));
        Login third = toLogin(refresh(second.refresh()));
        String family = familyOf(l.refresh());

        MvcResult reuse = refresh(l.refresh());
        assertThat(reuse.getResponse().getStatus()).isEqualTo(401);
        assertThat(reuse.getResponse().getContentType()).startsWith("application/problem+json");

        Integer active = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid AND revoked_at IS NULL", Integer.class, family);
        assertThat(active).isZero();
        // El token más reciente (legítimo hasta ahora) también quedó inservible.
        assertThat(refresh(third.refresh()).getResponse().getStatus()).isEqualTo(401);
        Integer reuseEvents = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOKEN_REUSE_DETECTED' AND decision = 'DENY'", Integer.class);
        assertThat(reuseEvents).isPositive();
    }

    @Test
    void reuseOfOneFamilyDoesNotAffectAnotherSessionOfTheSameUser() throws Exception {
        Login a = login("EMP004");
        Login b = login("EMP004");
        Login a2 = toLogin(refresh(a.refresh()));
        assertThat(refresh(a.refresh()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(a2.refresh()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(b.refresh()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logoutInvalidatesTheRefreshToken() throws Exception {
        Login l = login("ACC001");
        MvcResult out = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, l.refresh()))).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(204);
        assertThat(out.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0").contains("HttpOnly");
        assertThat(refresh(l.refresh()).getResponse().getStatus()).isEqualTo(401);
        // Logout sin cookie o con basura: siempre 204 (no revela estado).
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, "basura"))).andExpect(status().isNoContent());
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        Login l = login("COM001");
        tokenTime.advance(Duration.ofDays(7).plusSeconds(1));
        MvcResult r = refresh(l.refresh());
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void expiredAccessTokenIsRejectedWithoutClockSkew() throws Exception {
        Login l = login("DIR001");
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + l.access())).andExpect(status().isOk());
        tokenTime.advance(Duration.ofMinutes(10).plusSeconds(1));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + l.access()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void missingOrUnknownRefreshCookieIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
        assertThat(refresh("no-existe").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshTokenIsNeverStoredInPlainText() throws Exception {
        Login l = login("EMP001");
        Login next = toLogin(refresh(l.refresh()));
        for (String raw : List.of(l.refresh(), next.refresh())) {
            String hash = sha256(raw);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, hash)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE refresh_tokens::text LIKE '%' || ? || '%'", Integer.class, raw)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE audit_logs::text LIKE '%' || ? || '%'", Integer.class, raw)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE audit_logs::text LIKE '%' || ? || '%'", Integer.class, l.access())).isZero();
        assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class)).allMatch(h -> h.matches("[0-9a-f]{64}"));
    }

    @Test
    void badCredentialsGiveTheSame401ForUnknownUserAndWrongPassword() throws Exception {
        String unknown = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"NOBODY\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String wrong = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"EMP001\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(unknown).get("detail")).isEqualTo(mapper.readTree(wrong).get("detail"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_FAILED'", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void meComesFromTheTokenAndIgnoresClientIdentityFields() throws Exception {
        Login emp = login("EMP001");
        String body = mvc.perform(get("/api/v1/users/me?employee_id=EMP002&role=MANAGER")
                        .header("Authorization", "Bearer " + emp.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value("EMP001"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("salary").doesNotContain("password").doesNotContain("dify");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'IGNORED_CLIENT_FIELD'
                  AND details->'fields' @> '["employee_id","role"]'::jsonb
                """, Integer.class)).isPositive();
    }

    @Test
    void unauthenticatedAndForgedTokensGetProblemDetails401() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:segurplus:problem:unauthorized"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer abc.def.ghi")).andExpect(status().isUnauthorized());
        // Token con la cabecera alg=none
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"" + java.util.UUID.randomUUID() + "\",\"role\":\"MANAGER\"}").getBytes(StandardCharsets.UTF_8)) + ".";
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + none)).andExpect(status().isUnauthorized());
    }
}
