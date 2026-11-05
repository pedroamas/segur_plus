package com.segurplus.auth;

import com.segurplus.shared.SeedProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Siembra los 7 usuarios de desarrollo (brief §3) cuando {@code segurplus.seed.enabled=true}. Las contraseñas salen de
 * la configuración y se guardan con bcrypt; no hay contraseñas ni hashes en ficheros versionados. Idempotente: si el
 * usuario existe, solo actualiza el hash (permite rotar la contraseña de desarrollo). Los salarios son ficticios.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "segurplus.seed.enabled", havingValue = "true")
class DevUserSeeder implements ApplicationRunner {

    private record Seed(String code, String fullName, Role role, String department, String salary) {
    }

    private static final java.util.List<Seed> USERS = java.util.List.of(
            new Seed("DIR001", "Director General", Role.MANAGER, "DIRECCION", "6500.00"),
            new Seed("ACC001", "Responsable Financiero", Role.ACCOUNTANT, "CONTABILIDAD", "3800.00"),
            new Seed("COM001", "Responsable Comercial", Role.SALES_MANAGER, "COMERCIAL", "3900.00"),
            new Seed("EMP001", "Pedro Gómez", Role.EMPLOYEE, "COMERCIAL", "2300.00"),
            new Seed("EMP002", "Laura Fernández", Role.EMPLOYEE, "COMERCIAL", "2300.00"),
            new Seed("EMP003", "Martín Rodríguez", Role.EMPLOYEE, "COMERCIAL", "2300.00"),
            new Seed("EMP004", "Sofía Martínez", Role.EMPLOYEE, "COMERCIAL", "2300.00"));

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final SeedProperties seed;

    DevUserSeeder(JdbcClient jdbc, PasswordEncoder encoder, SeedProperties seed) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Seed u : USERS) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO users (id, employee_code, email, password_hash, full_name, role, department, monthly_salary,
                                       hire_date, dify_user_ref)
                    VALUES (:id, :code, :email, :hash, :name, :role, :department, :salary, DATE '2026-09-01', :difyRef)
                    ON CONFLICT (employee_code) DO UPDATE SET password_hash = EXCLUDED.password_hash, active = true
                    """)
                    .param("id", id)
                    .param("code", u.code())
                    .param("email", u.code().toLowerCase() + "@segurplus.example")
                    .param("hash", encoder.encode(seed.passwordFor(u.code())))
                    .param("name", u.fullName())
                    .param("role", u.role().name())
                    .param("department", u.department())
                    .param("salary", new java.math.BigDecimal(u.salary()))
                    .param("difyRef", "u_" + id)
                    .update();
        }
        log.info("Seeded {} development users", USERS.size());
    }
}
