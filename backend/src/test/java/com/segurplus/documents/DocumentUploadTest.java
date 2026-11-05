package com.segurplus.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.Containers;
import com.segurplus.support.IntegrationTest;
import com.segurplus.support.RestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Subida solo MANAGER: tipo real, extensión, tamaño, reglas de metadatos, MinIO privado y auditoría. */
class DocumentUploadTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @AfterEach
    void removeUploadedDocuments() {
        // Los documentos de estos tests llevan código T..: no deben contaminar la matriz ni los listados de otras clases.
        jdbc.update("DELETE FROM documents WHERE code LIKE 'T%'");
    }

    private RestSupport rest() {
        return new RestSupport(mvc, mapper);
    }

    private MockHttpServletRequestBuilder upload(String filename, byte[] content, String code) {
        return upload(filename, content, code, "GENERAL", "MANUAL", "INTERNAL");
    }

    private MockHttpServletRequestBuilder upload(String filename, byte[] content, String code, String kb, String category,
            String classification) {
        return multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", filename, "application/octet-stream", content))
                .param("code", code).param("title", "Documento de prueba").param("kb", kb)
                .param("category", category).param("classification", classification);
    }

    private MockHttpServletResponse send(String user, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", rest().bearer(user))).andReturn().getResponse();
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class);
    }

    @Test
    void managerUploadsAPdfThatIsStoredInMinioAndServedBackIntact() throws Exception {
        byte[] pdf = FixtureFiles.pdf("Subida de prueba");
        MockHttpServletResponse r = send("DIR001", upload("manual.pdf", pdf, "T01").param("allowedRoles", "EMPLOYEE"));
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode body = mapper.readTree(r.getContentAsString());
        UUID id = UUID.fromString(body.get("id").asText());
        assertThat(r.getHeader("Location")).isEqualTo("/api/v1/documents/" + id);
        assertThat(body.get("fileType").asText()).isEqualTo("PDF");
        assertThat(body.get("indexStatus").asText()).isEqualTo("PENDING");
        assertThat(body.get("checksumSha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)));

        // MANAGER siempre queda en allowed_roles aunque no se pida.
        assertThat(jdbc.queryForObject("SELECT allowed_roles::text FROM documents WHERE id = ?", String.class, id)).contains("MANAGER").contains("EMPLOYEE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'DOCUMENT_UPLOAD' AND resource_id = ?", Integer.class, id.toString())).isEqualTo(1);

        // Descarga por backend: idéntica; el empleado la ve (EMPLOYEE en allowed_roles) y la contable no.
        MockHttpServletResponse dl = mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", rest().bearer("EMP002"))).andReturn().getResponse();
        assertThat(dl.getStatus()).isEqualTo(200);
        assertThat(dl.getContentAsByteArray()).isEqualTo(pdf);
        assertThat(mvc.perform(get("/api/v1/documents/" + id).header("Authorization", rest().bearer("ACC001"))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void minioBucketIsPrivateAndTheApiNeverReturnsStorageUrls() throws Exception {
        MockHttpServletResponse r = send("DIR001", upload("p.pdf", FixtureFiles.pdf("x"), "T02"));
        String key = jdbc.queryForObject("SELECT storage_key FROM documents WHERE code = 'T02'", String.class);
        assertThat(r.getContentAsString()).doesNotContain(key).doesNotContain(Containers.MINIO.getS3URL());
        HttpClient http = HttpClient.newHttpClient();
        for (String url : List.of(Containers.MINIO.getS3URL() + "/segurplus-documents/" + key, Containers.MINIO.getS3URL() + "/segurplus-documents/")) {
            HttpResponse<String> anon = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(anon.statusCode()).as("anonymous GET %s", url).isEqualTo(403);
        }
    }

    @Test
    void everyNonManagerRoleGets403AndAnAuditedDenyAndNothingIsStored() throws Exception {
        int before = documentCount();
        for (String user : List.of("ACC001", "COM001", "EMP001")) {
            MockHttpServletResponse r = send(user, upload("p.pdf", FixtureFiles.pdf("x"), "T03")
                    .param("role", "MANAGER").param("employee_id", "DIR001").param("assistantId", "x"));
            assertThat(r.getStatus()).as(user).isEqualTo(403);
            assertThat(r.getContentType()).startsWith("application/problem+json");
        }
        assertThat(documentCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ACCESS_DENIED' AND resource_type = 'DOCUMENT' AND resource_id IS NULL
                  AND reason_code = 'MANAGER_ONLY'
                """, Integer.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void contentMustMatchTheDeclaredExtension() throws Exception {
        int before = documentCount();
        byte[] pdf = FixtureFiles.pdf("x");
        byte[] xlsx = FixtureFiles.xlsx("T");
        assertThat(send("DIR001", upload("fake.pdf", "esto no es un pdf".getBytes(StandardCharsets.UTF_8), "T04")).getStatus()).isEqualTo(415);
        assertThat(send("DIR001", upload("fake.pdf", new byte[] {'M', 'Z', 0, 3, 0, 0}, "T04")).getStatus()).isEqualTo(415);
        assertThat(send("DIR001", upload("real.csv", pdf, "T04")).getStatus()).isEqualTo(415);          // PDF real con extensión csv
        assertThat(send("DIR001", upload("real.pdf", xlsx, "T04")).getStatus()).isEqualTo(415);         // XLSX real con extensión pdf
        assertThat(send("DIR001", upload("fake.xlsx", "PK\u0003\u0004 no es un zip".getBytes(StandardCharsets.ISO_8859_1), "T04")).getStatus()).isEqualTo(415);
        assertThat(send("DIR001", upload("data.json", "{no es json".getBytes(StandardCharsets.UTF_8), "T04")).getStatus()).isEqualTo(415);
        assertThat(send("DIR001", upload("run.exe", pdf, "T04")).getStatus()).isEqualTo(415);
        assertThat(send("DIR001", upload("noext", pdf, "T04")).getStatus()).isEqualTo(415);
        assertThat(documentCount()).isEqualTo(before);
        // Los tipos válidos sí entran.
        assertThat(send("DIR001", upload("ok.xlsx", xlsx, "T05")).getStatus()).isEqualTo(201);
        assertThat(send("DIR001", upload("ok.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "T06")).getStatus()).isEqualTo(201);
        assertThat(send("DIR001", upload("ok.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "T07")).getStatus()).isEqualTo(201);
    }

    @Test
    void sizeLimitAndEmptyFilesAreRejected() throws Exception {
        int before = documentCount();
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(FixtureFiles.pdf("x"), 0, big, 0, 8);
        assertThat(send("DIR001", upload("big.pdf", big, "T08")).getStatus()).isEqualTo(413);
        assertThat(send("DIR001", upload("empty.pdf", new byte[0], "T08")).getStatus()).isIn(422, 400);
        assertThat(documentCount()).isEqualTo(before);
    }

    @Test
    void metadataRulesMirrorTheDatabaseChecks() throws Exception {
        byte[] pdf = FixtureFiles.pdf("x");
        assertThat(send("DIR001", upload("a.pdf", pdf, "T09", "GENERAL", "MANUAL", "RESTRICTED").param("allowedRoles", "EMPLOYEE")).getStatus()).isEqualTo(422);
        assertThat(send("DIR001", upload("a.pdf", pdf, "T09", "EMPLEADOS", "CONTRACT", "CONFIDENTIAL")).getStatus()).isEqualTo(422);   // sin propietario
        UUID emp1 = jdbc.queryForObject("SELECT id FROM users WHERE employee_code = 'EMP001'", UUID.class);
        assertThat(send("DIR001", upload("a.pdf", pdf, "T09", "EMPLEADOS", "CONTRACT", "CONFIDENTIAL").param("ownerId", emp1.toString()).param("allowedRoles", "EMPLOYEE")).getStatus()).isEqualTo(422);
        assertThat(send("DIR001", upload("a.pdf", pdf, "T09").param("ownerId", UUID.randomUUID().toString())).getStatus()).isEqualTo(422); // propietario inexistente
        assertThat(send("DIR001", upload("a.pdf", pdf, "bad code!")).getStatus()).isEqualTo(422);
        assertThat(send("DIR001", upload("a.pdf", pdf, "T09", "NOPE", "MANUAL", "INTERNAL")).getStatus()).isEqualTo(400);
        assertThat(documentCount()).isEqualTo(32);

        // Documento de empleado válido: solo el propietario (y MANAGER) lo ven.
        MockHttpServletResponse ok = send("DIR001", upload("a.pdf", pdf, "T10", "EMPLEADOS", "CONTRACT", "RESTRICTED").param("ownerId", emp1.toString()));
        assertThat(ok.getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(mapper.readTree(ok.getContentAsString()).get("id").asText());
        assertThat(mvc.perform(get("/api/v1/documents/" + id).header("Authorization", rest().bearer("EMP001"))).andReturn().getResponse().getStatus()).isEqualTo(200);
        for (String other : Set.of("EMP002", "ACC001", "COM001")) {
            assertThat(mvc.perform(get("/api/v1/documents/" + id).header("Authorization", rest().bearer(other))).andReturn().getResponse().getStatus()).as(other).isEqualTo(404);
        }
    }

    @Test
    void duplicateCodeIsAConflictAndLeavesNoOrphanObject() throws Exception {
        assertThat(send("DIR001", upload("a.pdf", FixtureFiles.pdf("x"), "T11")).getStatus()).isEqualTo(201);
        assertThat(send("DIR001", upload("a.pdf", FixtureFiles.pdf("y"), "T11")).getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE code = 'T11'", Integer.class)).isEqualTo(1);
    }
}
