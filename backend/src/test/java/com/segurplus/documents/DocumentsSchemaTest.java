package com.segurplus.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.segurplus.support.Containers;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Migraciones Flyway reales contra PostgreSQL 16 y los CHECK de {@code documents} (criterio 2 de F1). */
class DocumentsSchemaTest {

    private static JdbcTemplate jdbc;
    private static UUID userId;

    @BeforeAll
    static void migrate() {
        // Esquema aislado para no interferir con los tests con contexto Spring.
        DataSource ds = new DriverManagerDataSource(Containers.POSTGRES.getJdbcUrl(),
                Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS schema_test");
        Flyway.configure().dataSource(ds).schemas("schema_test").defaultSchema("schema_test")
                .locations("classpath:db/migration").load().migrate();
        String url = Containers.POSTGRES.getJdbcUrl() + (Containers.POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=schema_test";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword()));
        userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, employee_code, email, password_hash, full_name, role, department, monthly_salary,
                                   hire_date, dify_user_ref)
                VALUES (?, 'DIR999', 'dir999@example.test', 'x', 'Test', 'MANAGER', 'DIRECCION', 1.00, DATE '2026-09-01', ?)
                """, userId, "u_" + UUID.randomUUID());
    }

    @Test
    void createsTheTwelveTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'schema_test' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder("users", "refresh_tokens", "documents", "conversations", "messages",
                "customers", "policies", "commissions", "invoices", "expenses", "sales_targets", "audit_logs");
    }

    @Test
    void validDocumentIsAccepted() {
        insert("OK1", "GENERAL", "INTERNAL", null, "ARRAY['MANAGER','EMPLOYEE']");
    }

    @Test
    void rejectsAllowedRolesWithoutManager() {
        assertViolation("ck_documents_roles", () -> insert("V1", "FINANZAS", "CONFIDENTIAL", null, "ARRAY['ACCOUNTANT']"));
    }

    @Test
    void rejectsRestrictedWithEmployeeRole() {
        assertViolation("ck_documents_restricted",
                () -> insert("V2", "DIRECCION", "RESTRICTED", null, "ARRAY['MANAGER','EMPLOYEE']"));
    }

    @Test
    void rejectsEmpleadosKbWithEmployeeRole() {
        assertViolation("ck_documents_empleados",
                () -> insert("V3", "EMPLEADOS", "CONFIDENTIAL", userId, "ARRAY['MANAGER','EMPLOYEE']"));
    }

    @Test
    void rejectsEmpleadosKbWithoutOwner() {
        assertViolation("ck_documents_empleados", () -> insert("V4", "EMPLEADOS", "CONFIDENTIAL", null, "ARRAY['MANAGER']"));
    }

    private void insert(String code, String kb, String classification, UUID owner, String rolesArray) {
        jdbc.update("""
                INSERT INTO documents (code, title, file_type, category, kb, classification, owner_id, allowed_roles,
                                       storage_key, checksum_sha256, size_bytes, created_by)
                VALUES (?, 't', 'PDF', 'REPORT', ?, ?, ?, %s::text[], ?, repeat('a', 64), 1, ?)
                """.formatted(rolesArray), code, kb, classification, owner, "k/" + code, userId);
    }

    private static void assertViolation(String constraint, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }
}
