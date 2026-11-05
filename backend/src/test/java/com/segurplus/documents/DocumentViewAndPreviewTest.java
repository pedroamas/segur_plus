package com.segurplus.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.IntegrationTest;
import com.segurplus.support.RestSupport;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Visor PDF inline, preview Excel en servidor, cabeceras de descarga y ausencia de URLs al almacén. */
class DocumentViewAndPreviewTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private UUID id(String code) {
        return jdbc.queryForObject("SELECT id FROM documents WHERE code = ?", UUID.class, code);
    }

    private MockHttpServletResponse call(String user, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", new RestSupport(mvc, mapper).bearer(user))).andReturn().getResponse();
    }

    @Test
    void pdfIsServedInlineWithHardenedHeaders() throws Exception {
        MockHttpServletResponse r = call("EMP001", "/api/v1/documents/" + id("D14") + "/view"); // contrato propio (EMP001 es propietario)
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).isEqualTo("application/pdf");
        assertThat(r.getHeader("Content-Disposition")).startsWith("inline").contains("D14.pdf");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("Content-Security-Policy")).contains("sandbox");
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat(new String(r.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'DOCUMENT_READ' AND resource_id = ?", Integer.class, id("D14").toString())).isPositive();
    }

    @Test
    void downloadIsAnAttachmentAndNotInline() throws Exception {
        MockHttpServletResponse r = call("EMP001", "/api/v1/documents/" + id("D14") + "/download");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void viewOfNonPdfAndPreviewOfNonXlsxAreUnsupportedForAuthorizedUsers() throws Exception {
        assertThat(call("ACC001", "/api/v1/documents/" + id("D07") + "/view").getStatus()).isEqualTo(415);   // XLSX
        assertThat(call("EMP001", "/api/v1/documents/" + id("D14") + "/preview").getStatus()).isEqualTo(415); // PDF
    }

    @Test
    void excelPreviewShowsComputedValuesAndRespectsAuthorization() throws Exception {
        MockHttpServletResponse r = call("ACC001", "/api/v1/documents/" + id("D07") + "/preview");
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode sheet = mapper.readTree(r.getContentAsString()).get("sheets").get(0);
        assertThat(sheet.get("name").asText()).isEqualTo("Resumen");
        // La celda C2 es la fórmula A2*B2: llega el valor calculado, no la fórmula.
        assertThat(sheet.get("rows").get(1).toString()).isEqualTo("[\"2\",\"3\",\"6\"]");
        assertThat(r.getContentAsString()).doesNotContain("A2*B2");

        assertThat(call("EMP001", "/api/v1/documents/" + id("D07") + "/preview").getStatus()).isEqualTo(404);
        assertThat(call("COM001", "/api/v1/documents/" + id("D07") + "/preview").getStatus()).isEqualTo(404);
    }

    @Test
    void responsesNeverExposeStorageLocationsOrAccessRules() throws Exception {
        String detail = call("DIR001", "/api/v1/documents/" + id("D07")).getContentAsString();
        String list = call("DIR001", "/api/v1/documents?size=100").getContentAsString();
        for (String body : new String[] {detail, list}) {
            assertThat(body).doesNotContain("storage").doesNotContain("http://").doesNotContain("https://")
                    .doesNotContain("allowedRoles").doesNotContain("allowed_roles").doesNotContain("documents/");
        }
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        for (String path : new String[] {"/api/v1/documents", "/api/v1/documents/" + id("D26"), "/api/v1/documents/" + id("D26") + "/download"}) {
            MockHttpServletResponse r = mvc.perform(get(path)).andReturn().getResponse();
            assertThat(r.getStatus()).as(path).isEqualTo(401);
            assertThat(r.getContentType()).startsWith("application/problem+json");
        }
    }
}
