package com.segurplus.documents;

import com.segurplus.auth.Role;
import com.segurplus.documents.api.UploadMetadata;
import com.segurplus.shared.SeedProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Siembra documentos de PRUEBA diminutos a partir de {@code permissions-spec.yaml} (una entrada = un fixture) para ejercitar
 * la matriz completa. No es el dataset real (F2). Idempotente por código. Solo con {@code segurplus.seed.enabled=true} y
 * {@code segurplus.seed.documents-spec-path} informado.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "segurplus.seed.enabled", havingValue = "true")
class DevDocumentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDocumentSeeder.class);
    /** Tipos de fichero de los fixtures (el YAML no los declara): XLSX para finanzas, CSV para actividad comercial, JSON para el KPI. */
    private static final List<String> XLSX = List.of("D07", "D08", "D09", "D10", "D12");
    private static final List<String> CSV = List.of("D15", "D18", "D21", "D24");
    private static final List<String> JSON = List.of("D03");

    private final SeedProperties seed;
    private final JdbcClient jdbc;
    private final DocumentRepository documents;
    private final DocumentService service;

    DevDocumentSeeder(SeedProperties seed, JdbcClient jdbc, DocumentRepository documents, DocumentService service) {
        this.seed = seed;
        this.jdbc = jdbc;
        this.documents = documents;
        this.service = service;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(ApplicationArguments args) throws IOException {
        if (seed.documentsSpecPath() == null || seed.documentsSpecPath().isBlank()) {
            return;
        }
        Map<String, Object> spec;
        try (var in = Files.newInputStream(Path.of(seed.documentsSpecPath()))) {
            spec = new Yaml().load(in);
        }
        UUID creator = userId("DIR001");
        int created = 0;
        for (Map<String, Object> d : (List<Map<String, Object>>) spec.get("documents")) {
            String code = (String) d.get("code");
            if (documents.existsByCode(code)) {
                continue;
            }
            FileType type = XLSX.contains(code) ? FileType.XLSX : CSV.contains(code) ? FileType.CSV : JSON.contains(code) ? FileType.JSON : FileType.PDF;
            String owner = (String) d.get("owner");
            List<Role> roles = ((List<String>) d.get("allowed_roles")).stream().map(Role::valueOf).toList();
            UploadMetadata meta = new UploadMetadata(code, "Fixture " + code, Kb.valueOf((String) d.get("kb")),
                    DocCategory.valueOf((String) d.get("cat")), Classification.valueOf((String) d.get("cls")),
                    owner == null ? null : userId(owner), roles);
            service.createUnchecked(creator, meta, type, FixtureFiles.forType(type, code));
            created++;
        }
        log.info("Seeded {} fixture documents", created);
    }

    private UUID userId(String employeeCode) {
        return jdbc.sql("SELECT id FROM users WHERE employee_code = :code").param("code", employeeCode).query(UUID.class).single();
    }
}
