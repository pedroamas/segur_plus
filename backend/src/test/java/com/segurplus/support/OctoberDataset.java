package com.segurplus.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dataset de PRUEBA con las cifras ancla de octubre de 2026 (12 pólizas, 9.680 €, 968 €, 105,0 / 76,0 / 73,2 / 68,8 % y 80,7 %),
 * más ruido que distingue los alcances (póliza de COM001 en septiembre, cancelación de septiembre con su CLAWBACK, diciembre).
 * Mismo contenido que el de DashboardTest (F3): sirve para comprobar que el chat y el dashboard dan la misma cifra.
 */
public final class OctoberDataset {

    public static final List<String> EMPLOYEES = List.of("EMP001", "EMP002", "EMP003", "EMP004");
    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    private final JdbcTemplate jdbc;

    public OctoberDataset(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void load() {
        clean();
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, full_name) VALUES (?, 'C-TEST', 'Cliente de prueba')", customer);
        Map<String, List<String>> october = Map.of(
                "EMP001", List.of("1000.00", "1150.00", "1000.00"),
                "EMP002", List.of("780.00", "800.00", "700.00"),
                "EMP003", List.of("700.00", "650.00", "700.00"),
                "EMP004", List.of("700.00", "800.00", "700.00"));
        for (String code : EMPLOYEES) {
            List<String> premiums = october.get(code);
            for (int i = 0; i < premiums.size(); i++) {
                boolean cancelled = code.equals("EMP002") && i == 2;
                UUID policy = policy(code, customer, premiums.get(i), LocalDate.of(2026, 10, 5 + i), cancelled ? LocalDate.of(2026, 12, 1) : null);
                commission(code, policy, "ACCRUAL", LocalDate.of(2026, 10, 1), premiums.get(i));
            }
            target(code, LocalDate.of(2026, 10, 1), Map.of("EMP001", "3000.00", "EMP002", "3000.00", "EMP003", "2800.00", "EMP004", "3200.00").get(code));
        }
        UUID september = policy("EMP001", customer, "500.00", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 5));
        commission("EMP001", september, "ACCRUAL", LocalDate.of(2026, 9, 1), "500.00");
        commission("EMP001", september, "CLAWBACK", LocalDate.of(2026, 10, 1), "500.00");
        policy("COM001", customer, "777.00", LocalDate.of(2026, 9, 20), null);
        policy("EMP001", customer, "400.00", LocalDate.of(2026, 11, 3), null);
        policy("EMP003", customer, "300.00", LocalDate.of(2026, 12, 9), null);
    }

    public void clean() {
        jdbc.update("DELETE FROM commissions");
        jdbc.update("DELETE FROM policies");
        jdbc.update("DELETE FROM sales_targets");
        jdbc.update("DELETE FROM customers WHERE customer_number = 'C-TEST'");
    }

    private UUID userId(String code) {
        return jdbc.queryForObject("SELECT id FROM users WHERE employee_code = ?", UUID.class, code);
    }

    private UUID policy(String code, UUID customer, String premium, LocalDate sale, LocalDate cancelledAt) {
        UUID id = UUID.randomUUID();
        BigDecimal p = new BigDecimal(premium);
        jdbc.update("INSERT INTO policies (id, policy_number, employee_id, customer_id, product, insurer, premium, status, sale_date, "
                        + "start_date, renewal_date, cancelled_at, insurer_commission_rate, insurer_commission_amount) "
                        + "VALUES (?, ?, ?, ?, 'AUTO', 'Aseguradora', ?, ?, ?, ?, ?, ?, 0.2000, ?)",
                id, "P-A" + SEQ.incrementAndGet(), userId(code), customer, p, cancelledAt == null ? "ACTIVE" : "CANCELLED", sale, sale,
                sale.plusYears(1), cancelledAt, p.multiply(new BigDecimal("0.2")).setScale(2));
        return id;
    }

    private void commission(String code, UUID policy, String kind, LocalDate month, String base) {
        BigDecimal b = new BigDecimal(base);
        BigDecimal amount = b.multiply(new BigDecimal("0.1")).setScale(2);
        jdbc.update("INSERT INTO commissions (employee_id, policy_id, kind, period_month, base_amount, rate, amount) VALUES (?, ?, ?, ?, ?, 0.1000, ?)",
                userId(code), policy, kind, month, b, kind.equals("CLAWBACK") ? amount.negate() : amount);
    }

    private void target(String code, LocalDate month, String amount) {
        jdbc.update("INSERT INTO sales_targets (employee_id, period_month, target_amount) VALUES (?, ?, ?)", userId(code), month, new BigDecimal(amount));
    }
}
