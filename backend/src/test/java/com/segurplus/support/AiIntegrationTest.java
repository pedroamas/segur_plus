package com.segurplus.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link IntegrationTest} con Dify simulado ({@link FakeDify}) y el indexador activo (sin job periódico: se invoca a mano). */
public abstract class AiIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("segurplus.ai.dify.base-url", FakeDify::baseUrl);
        r.add("segurplus.ai.dify.knowledge-api-key", () -> FakeDify.KNOWLEDGE_KEY);
        r.add("segurplus.ai.dify.app-api-key", () -> FakeDify.APP_KEY);
        r.add("segurplus.ai.dify.planner-api-key", () -> FakeDify.PLANNER_KEY);
        r.add("segurplus.ai.indexer.enabled", () -> "true");
        r.add("segurplus.ai.indexer.interval-seconds", () -> "3600");
        r.add("segurplus.ai.indexer.poll-timeout-seconds", () -> "5");
        r.add("segurplus.ai.dify.first-response-timeout", () -> "5s");
    }

    @Autowired
    JdbcTemplate aiJdbc;

    /** Deja el índice como lo encuentran las demás clases de test (documentos sembrados PENDING y sin copia en Dify). */
    @AfterEach
    void resetIndexState() {
        aiJdbc.update("DELETE FROM documents WHERE code LIKE 'ZZ%'");
        aiJdbc.update("UPDATE documents SET deleted_at = NULL WHERE deleted_at IS NOT NULL");
        aiJdbc.update("UPDATE documents SET index_status = 'PENDING', dify_dataset_id = NULL, dify_document_id = NULL, indexed_at = NULL");
    }
}
