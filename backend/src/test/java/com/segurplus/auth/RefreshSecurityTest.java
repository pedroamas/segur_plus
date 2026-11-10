package com.segurplus.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F6, criterio 3: tests de SEGURIDAD del refresh (complementan SessionTest de F1, que prueba la mecánica). Aquí se ataca:
 * cookie (HttpOnly/Secure/SameSite/Path) en cada emisión, refresh solo por POST y solo por cookie (ni GET, ni cabecera, ni
 * query, ni cuerpo), reutilización ⇒ familia revocada + TOKEN_REUSE_DETECTED con metadatos y sin el token, refresh robado tras
 * logout ⇒ 401, access caducado ⇒ 401, confusión access/refresh y ausencia del token en CUALQUIER tabla (solo su hash).
 */
class RefreshSecurityTest extends IntegrationTest {

    private static final String COOKIE = "segurplus_refresh";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    record Session(String access, String refresh, MockHttpServletResponse response) {
    }

    private Session login(String code) throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(code, SEED_PASSWORD))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return new Session(mapper.readTree(res.getContentAsString()).get("accessToken").asText(), res.getCookie(COOKIE).getValue(), res);
    }

    private MockHttpServletResponse refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, token))).andReturn().getResponse();
    }

    private Session rotate(String token) throws Exception {
        MockHttpServletResponse res = refresh(token);
        assertThat(res.getStatus()).isEqualTo(200);
        return new Session(mapper.readTree(res.getContentAsString()).get("accessToken").asText(), res.getCookie(COOKIE).getValue(), res);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertHardenedCookie(MockHttpServletResponse res, String where) {
        String header = res.getHeader("Set-Cookie");
        assertThat(header).as(where).contains(COOKIE + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        Cookie c = res.getCookie(COOKIE);
        assertThat(c.isHttpOnly()).as(where).isTrue();
        assertThat(c.getSecure()).as(where).isTrue();
        assertThat(c.getAttribute("SameSite")).as(where).isEqualTo("Strict");
        assertThat(c.getPath()).as(where).isEqualTo("/api/v1/auth");
        assertThat(header).as(where + ": sin Domain (solo el host que la emitió)").doesNotContainIgnoringCase("Domain=");
    }

    @Test
    void everyCookieIssuedOrClearedCarriesHttpOnlySecureAndSameSiteStrict() throws Exception {
        Session first = login("EMP001");
        assertHardenedCookie(first.response(), "login");
        Session second = rotate(first.refresh());
        assertHardenedCookie(second.response(), "refresh");
        MockHttpServletResponse out = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, second.refresh()))).andReturn().getResponse();
        assertThat(out.getHeader("Set-Cookie")).contains("Max-Age=0").contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        // lifetime del refresh: 7 días exactos, no de sesión (para poder caducar) y no más
        assertThat(first.response().getCookie(COOKIE).getMaxAge()).isEqualTo((int) Duration.ofDays(7).toSeconds());
        // el cuerpo de login/refresh no lleva el refresh token ni se devuelve en ninguna cabecera distinta de Set-Cookie
        for (Session s : List.of(first, second)) {
            assertThat(s.response().getContentAsString()).doesNotContain(s.refresh());
            for (String h : s.response().getHeaderNames()) {
                if (!h.equalsIgnoreCase("Set-Cookie")) {
                    assertThat(s.response().getHeaders(h).toString()).as("cabecera " + h).doesNotContain(s.refresh());
                }
            }
            assertThat(s.response().getHeader("Cache-Control")).as("respuestas de sesión no cacheables").containsIgnoringCase("no-store");
        }
    }

    @Test
    void refreshIsOnlyAcceptedAsAPostWithTheCookie() throws Exception {
        Session s = login("EMP002");
        String family = jdbc.queryForObject("SELECT family_id::text FROM refresh_tokens WHERE token_hash = ?", String.class, sha256(s.refresh()));
        // otros métodos HTTP con la cookie: no rotan ni emiten nada
        for (var req : List.of(get("/api/v1/auth/refresh"), put("/api/v1/auth/refresh"), delete("/api/v1/auth/refresh"), head("/api/v1/auth/refresh"))) {
            MvcResult r = mvc.perform(req.cookie(new Cookie(COOKIE, s.refresh()))).andReturn();
            assertThat(r.getResponse().getStatus()).as(req.toString()).isIn(401, 405);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("accessToken");
            assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
        }
        // el token en cabecera, query, cuerpo JSON o formulario NO cuenta: solo la cookie
        List<MockHttpServletResponse> attempts = List.of(
                mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer " + s.refresh())).andReturn().getResponse(),
                mvc.perform(post("/api/v1/auth/refresh").header("X-Refresh-Token", s.refresh())).andReturn().getResponse(),
                mvc.perform(post("/api/v1/auth/refresh").header("Cookie-Refresh", s.refresh())).andReturn().getResponse(),
                mvc.perform(post("/api/v1/auth/refresh?refreshToken=" + s.refresh() + "&" + COOKIE + "=" + s.refresh())).andReturn().getResponse(),
                mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + s.refresh() + "\"}")).andReturn().getResponse(),
                mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_FORM_URLENCODED).param(COOKIE, s.refresh())).andReturn().getResponse(),
                // access token usado como refresh y refresh usado como access token
                mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, s.access()))).andReturn().getResponse());
        for (MockHttpServletResponse res : attempts) {
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).doesNotContain("accessToken");
            assertThat(res.getHeader("Set-Cookie")).isNull();
        }
        assertThat(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + s.refresh())).andReturn().getResponse().getStatus()).isEqualTo(401);
        // ningún intento anterior rotó ni revocó nada: el token legítimo sigue siendo válido (y es el único activo de la familia)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid AND revoked_at IS NULL", Integer.class, family)).isEqualTo(1);
        assertThat(refresh(s.refresh()).getStatus()).isEqualTo(200);
    }

    @Test
    void reuseOfARotatedTokenRevokesTheFamilyAndAuditsTokenReuseDetectedWithMetadataOnly() throws Exception {
        Session a = login("EMP003");
        Session b = rotate(a.refresh());
        Session c = rotate(b.refresh());
        String family = jdbc.queryForObject("SELECT family_id::text FROM refresh_tokens WHERE token_hash = ?", String.class, sha256(a.refresh()));
        long before = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOKEN_REUSE_DETECTED'", Long.class);

        assertThat(refresh(a.refresh()).getStatus()).as("reutilización del primer token").isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid AND revoked_at IS NULL", Integer.class, family)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid", Integer.class, family)).isEqualTo(3);
        assertThat(refresh(c.refresh()).getStatus()).as("el token más reciente también muere").isEqualTo(401);
        assertThat(refresh(b.refresh()).getStatus()).isEqualTo(401);
        long after = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOKEN_REUSE_DETECTED'", Long.class);
        assertThat(after).as("un evento por reutilización detectada").isGreaterThanOrEqualTo(before + 1);

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT user_id::text AS user_id, decision, reason_code, channel, details::text AS details, correlation_id
                FROM audit_logs WHERE action = 'TOKEN_REUSE_DETECTED' ORDER BY occurred_at DESC LIMIT 1""");
        String userId = jdbc.queryForObject("SELECT id::text FROM users WHERE employee_code = 'EMP003'", String.class);
        assertThat(event.get("user_id")).isEqualTo(userId);
        assertThat(event.get("decision")).isEqualTo("DENY");
        assertThat(event.toString()).doesNotContain(a.refresh()).doesNotContain(b.refresh()).doesNotContain(c.refresh())
                .doesNotContain(sha256(a.refresh())).doesNotContain(a.access());
        // y la sesión de otro usuario no se ve afectada
        Session other = login("EMP004");
        assertThat(refresh(other.refresh()).getStatus()).isEqualTo(200);
    }

    @Test
    void aStolenRefreshTokenIsDeadAfterLogoutEvenIfItWasTheCurrentOne() throws Exception {
        Session s = login("ACC001");
        Session current = rotate(s.refresh());
        // el atacante copió el token vigente; la víctima cierra sesión
        MockHttpServletResponse out = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, current.refresh()))).andReturn().getResponse();
        assertThat(out.getStatus()).isEqualTo(204);
        assertThat(refresh(current.refresh()).getStatus()).as("el token vigente robado, tras logout").isEqualTo(401);
        assertThat(refresh(s.refresh()).getStatus()).as("el token ya rotado, tras logout").isEqualTo(401);
        String family = jdbc.queryForObject("SELECT family_id::text FROM refresh_tokens WHERE token_hash = ?", String.class, sha256(current.refresh()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE family_id = ?::uuid AND revoked_at IS NULL", Integer.class, family)).isZero();
        // logout solo cierra SU familia: otra sesión del mismo usuario sigue viva
        Session other = login("ACC001");
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, current.refresh()))).andReturn();
        assertThat(refresh(other.refresh()).getStatus()).isEqualTo(200);
        // logout por GET no hace nada
        Session keep = login("COM001");
        assertThat(mvc.perform(get("/api/v1/auth/logout").cookie(new Cookie(COOKIE, keep.refresh()))).andReturn().getResponse().getStatus()).isIn(401, 405);
        assertThat(refresh(keep.refresh()).getStatus()).isEqualTo(200);
    }

    @Test
    void expiredAndTamperedAccessTokensAreRejectedEverywhere() throws Exception {
        Session s = login("DIR001");
        String[] parts = s.access().split("\\.");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        // payload alterado (role) con la firma original ⇒ 401
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.toString().replace("\"MANAGER\"", "\"EMPLOYEE\"").replace("\"EMPLOYEE\"", "\"MANAGER\"").getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        String emp = login("EMP001").access();
        String[] ep = emp.split("\\.");
        String escalated = ep[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.readTree(Base64.getUrlDecoder().decode(ep[1])).toString().replace("EMPLOYEE", "MANAGER").getBytes(StandardCharsets.UTF_8)) + "." + ep[2];
        for (String bad : List.of(forged.equals(s.access()) ? "x.y.z" : forged, escalated, parts[0] + "." + parts[1] + ".", parts[0] + "." + parts[1] + "." + parts[2].substring(1) + "A")) {
            assertThat(mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + bad)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + escalated)).andReturn().getResponse().getStatus()).as("escalado de rol por payload").isEqualTo(401);
        // caducado ⇒ 401 en REST, listados y chat (mismo filtro)
        tokenTime.advance(Duration.ofMinutes(15).plusSeconds(1));
        for (String path : List.of("/api/v1/users/me", "/api/v1/documents", "/api/v1/dashboard", "/api/v1/chat/conversations")) {
            MockHttpServletResponse res = mvc.perform(get(path).header("Authorization", "Bearer " + s.access())).andReturn().getResponse();
            assertThat(res.getStatus()).as(path).isEqualTo(401);
            assertThat(res.getContentType()).startsWith("application/problem+json");
        }
    }

    @Test
    void refreshForADisabledUserIsRejected() throws Exception {
        Session s = login("EMP004");
        jdbc.update("UPDATE users SET active = false WHERE employee_code = 'EMP004'");
        try {
            assertThat(refresh(s.refresh()).getStatus()).isEqualTo(401);
        } finally {
            jdbc.update("UPDATE users SET active = true WHERE employee_code = 'EMP004'");
        }
    }

    @Test
    void tokensAreHighEntropyUniqueAndOnlyTheirHashIsStoredInAnyTable() throws Exception {
        Set<String> seen = new HashSet<>();
        Session s = login("EMP001");
        seen.add(s.refresh());
        Session r = rotate(s.refresh());
        seen.add(r.refresh());
        for (int i = 0; i < 20; i++) {
            seen.add(login("EMP00" + (1 + i % 4)).refresh());
        }
        assertThat(seen).hasSize(22);
        for (String t : seen) {
            assertThat(Base64.getUrlDecoder().decode(t).length).as("≥ 256 bits").isGreaterThanOrEqualTo(32);
        }
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", String.class);
        assertThat(tables).contains("refresh_tokens", "audit_logs", "users", "messages");
        for (String table : tables) {
            for (String raw : List.of(s.refresh(), r.refresh())) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE '%' || ? || '%'", Integer.class, raw))
                        .as("token en claro en la tabla " + table).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE '%' || ? || '%'", Integer.class, s.access()))
                    .as("access token en la tabla " + table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, sha256(s.refresh()))).isEqualTo(1);
        // el rastro de auditoría de la sesión lleva solo metadatos
        String audit = jdbc.queryForList("SELECT action || coalesce(reason_code,'') || coalesce(details::text,'') FROM audit_logs", String.class).toString();
        assertThat(audit).doesNotContain(s.refresh()).doesNotContain(SEED_PASSWORD);
    }
}
