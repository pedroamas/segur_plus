package com.segurplus.support;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de los tests con contexto: PostgreSQL y MinIO reales (Testcontainers), semilla de usuarios y de los 32 documentos
 * de PRUEBA desde permissions-spec.yaml. Todos los secretos son aleatorios por ejecución (nada versionado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.TimeConfig.class)
public abstract class IntegrationTest {

    public static final String SEED_PASSWORD = "pw-" + UUID.randomUUID();
    static final String JWT_SECRET = "jwt-" + UUID.randomUUID() + UUID.randomUUID();

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        TestTokenTime testTokenTime() {
            return new TestTokenTime();
        }
    }

    @Autowired
    protected TestTokenTime tokenTime;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("segurplus.auth.jwt-secret", () -> JWT_SECRET);
        r.add("segurplus.auth.bcrypt-strength", () -> 4);
        r.add("segurplus.storage.endpoint", Containers.MINIO::getS3URL);
        r.add("segurplus.storage.access-key", Containers.MINIO::getUserName);
        r.add("segurplus.storage.secret-key", Containers.MINIO::getPassword);
        r.add("segurplus.seed.enabled", () -> "true");
        r.add("segurplus.seed.default-password", () -> SEED_PASSWORD);
        r.add("segurplus.seed.documents-spec-path", () -> "../docs/data-model/permissions-spec.yaml");
    }

    @AfterEach
    void resetTokenTime() {
        tokenTime.reset();
    }
}
