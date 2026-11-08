package com.segurplus.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.documents.DocumentChanged;
import com.segurplus.support.AiIntegrationTest;
import com.segurplus.support.FakeDify;
import com.segurplus.support.RestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F4: indexador con Dify simulado. Estados PENDING → INDEXING → INDEXED | FAILED, reintento, metadatos, subida del MANAGER
 * (evento DocumentChanged), cambio de metadatos y borrado.
 */
class DocumentIndexerTest extends AiIntegrationTest {

    @Autowired DocumentIndexer indexer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void freshDify() {
        FakeDify.reset();
        jdbc.update("DELETE FROM documents WHERE code LIKE 'ZZ%'");
        jdbc.update("UPDATE documents SET deleted_at = NULL WHERE deleted_at IS NOT NULL");
        jdbc.update("UPDATE documents SET index_status = 'PENDING', dify_dataset_id = NULL, dify_document_id = NULL, indexed_at = NULL");
    }

    private String status(String code) {
        return jdbc.queryForObject("SELECT index_status FROM documents WHERE code = ?", String.class, code);
    }

    @Test
    void indexesTheWholeSeedAndStoresDifyReferences() {
        indexer.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE index_status = 'INDEXED'", Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE dify_document_id IS NULL OR dify_dataset_id IS NULL OR indexed_at IS NULL",
                Integer.class)).isZero();
        // cada documento va al dataset de su KB
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE kb = 'EMPLEADOS' AND dify_dataset_id <> 'ds-empleados'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE kb = 'DIRECCION' AND dify_dataset_id <> 'ds-direccion'", Integer.class)).isZero();
        // el texto indexado lleva el código y el título del documento
        List<JsonNode> created = FakeDify.requestsTo("/v1/datasets/[^/]+/document/create-by-text");
        assertThat(created).hasSize(32);
        assertThat(created.get(0).get("text").asText()).startsWith("Documento ");
        assertThat(created.get(0).get("indexing_technique").asText()).isEqualTo("high_quality");
    }

    @Test
    void sendsTheFiveMetadataFieldsWithExactRoleDelimiters() {
        indexer.runOnce();
        List<JsonNode> ops = FakeDify.requestsTo("/v1/datasets/[^/]+/documents/metadata");
        assertThat(ops).hasSize(32);
        for (JsonNode op : ops) {
            JsonNode list = op.at("/operation_data/0/metadata_list");
            assertThat(list).hasSize(5);
            Map<String, String> byName = new java.util.HashMap<>();
            list.forEach(m -> byName.put(m.get("name").asText(), m.get("value").asText()));
            assertThat(byName.keySet()).containsExactlyInAnyOrder("doc_id", "kb", "classification", "allowed_roles", "owner_id");
            assertThat(byName.get("allowed_roles")).startsWith(",MANAGER").endsWith(",");
            assertThat(byName.get("doc_id")).matches("[0-9a-f-]{36}");
        }
        // los campos de metadatos se crean como mucho una vez por dataset y se cachean (5 × 4 datasets)
        assertThat(FakeDify.requestsTo("/v1/datasets/[^/]+/metadata").size()).isLessThanOrEqualTo(20);
    }

    @Test
    void failureMarksFailedAndTheJobRetriesAfterTheBackoff() {
        FakeDify.SERVER.stubFor(post(urlPathMatching("/v1/datasets/[^/]+/document/create-by-text")).willReturn(aResponse().withStatus(500)));
        indexer.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE index_status = 'FAILED'", Integer.class)).isEqualTo(32);
        // restablecida la instancia, un FAILED reciente espera el backoff…
        FakeDify.reset();
        indexer.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE index_status = 'FAILED'", Integer.class)).isEqualTo(32);
        // …y pasado el backoff se reintenta hasta INDEXED
        jdbc.update("UPDATE documents SET updated_at = now() - interval '1 hour'");
        indexer.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE index_status = 'INDEXED'", Integer.class)).isEqualTo(32);
    }

    @Test
    void difyReportingAnErrorMarksTheDocumentFailed() {
        FakeDify.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/v1/datasets/[^/]+/documents/[^/]+"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"indexing_status\":\"error\"}")));
        indexer.index(id("D04"));
        assertThat(status("D04")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT dify_document_id FROM documents WHERE code = 'D04'", String.class)).isNotNull();
    }

    @Test
    void indexingStuckInDifyIsRescuedByTheJob() {
        FakeDify.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/v1/datasets/[^/]+/documents/[^/]+"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"indexing_status\":\"indexing\"}")));
        indexer.index(id("D04"));
        assertThat(status("D04")).as("el sondeo agota su plazo y queda INDEXING").isEqualTo("INDEXING");
        FakeDify.reset(); // Dify termina
        indexer.runOnce();
        assertThat(status("D04")).isEqualTo("INDEXED");
    }

    @Test
    void managerUploadFiresDocumentChangedAndGetsIndexed() throws Exception {
        RestSupport rest = new RestSupport(mvc, mapper);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "zz.csv", "application/octet-stream", "a,b\n1,2\n".getBytes());
        mvc.perform(multipart("/api/v1/documents").file(file).param("code", "ZZ01").param("title", "Subido por el MANAGER")
                        .param("kb", "GENERAL").param("category", "MANUAL").param("classification", "INTERNAL").param("allowedRoles", "ACCOUNTANT")
                        .header("Authorization", rest.bearer("DIR001")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status("ZZ01")).isEqualTo("INDEXED"));
        JsonNode md = FakeDify.requestsTo("/v1/datasets/[^/]+/documents/metadata").stream()
                .filter(op -> op.toString().contains("\"" + jdbc.queryForObject("SELECT id FROM documents WHERE code='ZZ01'", String.class) + "\"")).findFirst().orElseThrow();
        assertThat(md.toString()).contains(",MANAGER,ACCOUNTANT,");
    }

    @Test
    void metadataChangeIsResentAndTheDocumentStaysIndexed() {
        indexer.runOnce();
        UUID id = id("D07");
        jdbc.update("UPDATE documents SET allowed_roles = ARRAY['MANAGER'] WHERE id = ?", id);
        FakeDify.SERVER.resetRequests();
        indexer.handle(new DocumentChanged(id, DocumentChanged.Kind.METADATA_CHANGED));
        List<JsonNode> ops = FakeDify.requestsTo("/v1/datasets/[^/]+/documents/metadata");
        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).toString()).contains("\",MANAGER,\"").doesNotContain("ACCOUNTANT");
        assertThat(status("D07")).isEqualTo("INDEXED");
    }

    @Test
    void reindexRecreatesTheDifyCopyWithoutDuplicates() {
        indexer.runOnce();
        UUID id = id("D04");
        String before = jdbc.queryForObject("SELECT dify_document_id FROM documents WHERE id = ?", String.class, id);
        indexer.handle(new DocumentChanged(id, DocumentChanged.Kind.REINDEX));
        String after = jdbc.queryForObject("SELECT dify_document_id FROM documents WHERE id = ?", String.class, id);
        assertThat(status("D04")).isEqualTo("INDEXED");
        assertThat(after).isNotEqualTo(before);
        FakeDify.SERVER.verify(1, deleteRequestedFor(urlPathMatching("/v1/datasets/[^/]+/documents/" + before)));
    }

    @Test
    void softDeletedDocumentsAreRemovedFromDifyByTheJob() {
        indexer.runOnce();
        UUID id = id("D05");
        String difyId = jdbc.queryForObject("SELECT dify_document_id FROM documents WHERE id = ?", String.class, id);
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", id);
        indexer.runOnce();
        FakeDify.SERVER.verify(1, deleteRequestedFor(urlPathMatching("/v1/datasets/[^/]+/documents/" + difyId)));
        assertThat(jdbc.queryForObject("SELECT dify_document_id FROM documents WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT index_status FROM documents WHERE id = ?", String.class, id)).isEqualTo("PENDING");
    }

    private UUID id(String code) {
        return jdbc.queryForObject("SELECT id FROM documents WHERE code = ?", UUID.class, code);
    }
}
