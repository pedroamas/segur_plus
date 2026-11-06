package com.segurplus.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.auth.Role;
import com.segurplus.auth.Subject;
import com.segurplus.support.IntegrationTest;
import com.segurplus.support.RestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F3: dashboard por rol sobre un dataset de PRUEBA con las cifras ancla de octubre de 2026 (12 pólizas, 9.680 €, 968 €,
 * 105,0 / 76,0 / 73,2 / 68,8 % y 80,7 %) más ruido que distingue los alcances (una póliza de la responsable comercial en
 * septiembre, una póliza de septiembre cancelada en octubre con su CLAWBACK, un gasto en borrador).
 */
class DashboardTest extends IntegrationTest {

    private static final List<String> EMPLOYEES = List.of("EMP001", "EMP002", "EMP003", "EMP004");
    private static final Map<String, String> PREMIUMS = Map.of("EMP001", "3150.00", "EMP002", "2280.00", "EMP003", "2050.00", "EMP004", "2200.00");
    private static final Map<String, String> COMMISSIONS = Map.of("EMP001", "315.00", "EMP002", "228.00", "EMP003", "205.00", "EMP004", "220.00");
    private static final Map<String, String> PERCENT = Map.of("EMP001", "105.0", "EMP002", "76.0", "EMP003", "73.2", "EMP004", "68.8");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SalesQuery sales;
    @Autowired TargetQuery targets;
    @Autowired ExpenseQuery expenses;

    private final AtomicInteger seq = new AtomicInteger();
    private RestSupport rest;

    @BeforeEach
    void dataset() {
        rest = new RestSupport(mvc, mapper);
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
                // la última póliza de EMP002 queda cancelada (en diciembre): sigue contando en las primas del mes de venta
                boolean cancelled = code.equals("EMP002") && i == 2;
                UUID policy = policy(code, customer, premiums.get(i), LocalDate.of(2026, 10, 5 + i), cancelled ? LocalDate.of(2026, 12, 1) : null);
                commission(code, policy, "ACCRUAL", LocalDate.of(2026, 10, 1), premiums.get(i));
            }
            target(code, LocalDate.of(2026, 10, 1), Map.of("EMP001", "3000.00", "EMP002", "3000.00", "EMP003", "2800.00", "EMP004", "3200.00").get(code));
            target(code, LocalDate.of(2026, 11, 1), "1000.00");
        }
        // septiembre: póliza de EMP001 cancelada en octubre (ACCRUAL de septiembre + CLAWBACK de octubre) y una de la responsable comercial
        UUID september = policy("EMP001", customer, "500.00", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 5));
        commission("EMP001", september, "ACCRUAL", LocalDate.of(2026, 9, 1), "500.00");
        commission("EMP001", september, "CLAWBACK", LocalDate.of(2026, 10, 1), "500.00");
        policy("COM001", customer, "777.00", LocalDate.of(2026, 9, 20), null);
        // noviembre y diciembre
        policy("EMP001", customer, "400.00", LocalDate.of(2026, 11, 3), null);
        policy("EMP003", customer, "300.00", LocalDate.of(2026, 12, 9), null);
        expense("2026-10-02", "4000.00", "APPROVED");
        expense("2026-10-20", "3250.00", "APPROVED");
        expense("2026-10-21", "999.00", "DRAFT");
        expense("2026-11-05", "500.00", "APPROVED");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM commissions");
        jdbc.update("DELETE FROM policies");
        jdbc.update("DELETE FROM sales_targets");
        jdbc.update("DELETE FROM expenses");
        jdbc.update("DELETE FROM customers WHERE customer_number = 'C-TEST'");
    }

    // --- cifras de octubre y alcance por cada uno de los 7 usuarios ---

    @Test
    void octoberFiguresForManager() throws Exception {
        JsonNode d = dashboard("DIR001", "?month=2026-10");
        assertThat(d.get("scope").asText()).isEqualTo("GLOBAL");
        assertThat(d.at("/sales/policyCount").asInt()).isEqualTo(12);
        assertThat(d.at("/sales/premiums/amount").asText()).isEqualTo("9680.00");
        assertThat(d.at("/sales/premiums/currency").asText()).isEqualTo("EUR");
        assertThat(d.at("/commissions/accrued/amount").asText()).isEqualTo("968.00");
        assertThat(d.at("/commissions/clawbacks/amount").asText()).isEqualTo("-50.00");
        assertThat(d.at("/commissions/net/amount").asText()).isEqualTo("918.00");
        assertThat(d.at("/policiesByStatus/active").asInt()).isEqualTo(11);
        assertThat(d.at("/policiesByStatus/cancelled").asInt()).isEqualTo(1);
        assertThat(d.at("/policiesByStatus/expired").asInt()).isZero();
        assertThat(d.at("/targets/employees").size()).isEqualTo(4);
        for (JsonNode row : d.at("/targets/employees")) {
            String code = row.get("employeeCode").asText();
            assertThat(row.at("/premiums/amount").asText()).isEqualTo(PREMIUMS.get(code));
            assertThat(row.get("percent").decimalValue()).isEqualByComparingTo(PERCENT.get(code));
        }
        assertThat(d.at("/targets/target/amount").asText()).isEqualTo("12000.00");
        assertThat(d.at("/targets/percent").decimalValue()).isEqualByComparingTo("80.7");
        assertThat(d.get("expenses").isNull()).isTrue();
    }

    @Test
    void accountantSeesGlobalAggregatesAndExpensesButNoTargets() throws Exception {
        JsonNode d = dashboard("ACC001", "?month=2026-10");
        assertThat(d.get("scope").asText()).isEqualTo("GLOBAL");
        assertThat(d.at("/sales/policyCount").asInt()).isEqualTo(12);
        assertThat(d.at("/sales/premiums/amount").asText()).isEqualTo("9680.00");
        assertThat(d.at("/commissions/accrued/amount").asText()).isEqualTo("968.00");
        assertThat(d.at("/policiesByStatus/active").asInt()).isEqualTo(11);
        assertThat(d.get("targets").isNull()).as("sin cumplimiento por empleado").isTrue();
        assertThat(d.at("/expenses/amount").asText()).as("solo gastos APPROVED").isEqualTo("7250.00");
        assertThat(d.toString()).doesNotContain("EMP00", "fullName");
    }

    @Test
    void salesManagerSeesTheCommercialTeamOnly() throws Exception {
        JsonNode oct = dashboard("COM001", "?month=2026-10");
        assertThat(oct.get("scope").asText()).isEqualTo("TEAM");
        assertThat(oct.at("/sales/premiums/amount").asText()).isEqualTo("9680.00");
        assertThat(oct.at("/targets/employees").size()).isEqualTo(4);
        assertThat(oct.at("/targets/percent").decimalValue()).isEqualByComparingTo("80.7");
        assertThat(oct.get("expenses").isNull()).isTrue();
        // septiembre: la póliza de la propia responsable (777 €) no es del equipo comercial; el director sí la ve en el global
        assertThat(dashboard("COM001", "?month=2026-09").at("/sales/premiums/amount").asText()).isEqualTo("500.00");
        assertThat(dashboard("DIR001", "?month=2026-09").at("/sales/premiums/amount").asText()).isEqualTo("1277.00");
    }

    @Test
    void everyEmployeeSeesOnlyTheirOwnFigures() throws Exception {
        for (String code : EMPLOYEES) {
            JsonNode d = dashboard(code, "?month=2026-10");
            assertThat(d.get("scope").asText()).isEqualTo("OWN");
            assertThat(d.at("/sales/policyCount").asInt()).isEqualTo(3);
            assertThat(d.at("/sales/premiums/amount").asText()).isEqualTo(PREMIUMS.get(code));
            assertThat(d.at("/commissions/accrued/amount").asText()).isEqualTo(COMMISSIONS.get(code));
            assertThat(d.at("/targets/employees").size()).isEqualTo(1);
            assertThat(d.at("/targets/employees/0/employeeCode").asText()).isEqualTo(code);
            assertThat(d.at("/targets/employees/0/percent").decimalValue()).isEqualByComparingTo(PERCENT.get(code));
            assertThat(d.at("/targets/percent").decimalValue()).isEqualByComparingTo(PERCENT.get(code));
            assertThat(d.get("expenses").isNull()).isTrue();
            for (String other : EMPLOYEES) {
                if (!other.equals(code)) {
                    assertThat(d.toString()).as(code + " no ve a " + other).doesNotContain(other);
                }
            }
        }
        // el clawback de EMP001 es suyo y de nadie más
        assertThat(dashboard("EMP001", "?month=2026-10").at("/commissions/clawbacks/amount").asText()).isEqualTo("-50.00");
        assertThat(dashboard("EMP002", "?month=2026-10").at("/commissions/clawbacks/amount").asText()).isEqualTo("0.00");
    }

    @Test
    void employeeCannotWidenScopeWithIdentityParameters() throws Exception {
        UUID other = userId("EMP002");
        String query = "?month=2026-10&employee_id=" + other + "&employeeId=" + other + "&role=MANAGER&employeeCode=EMP002&userId=" + other;
        JsonNode d = dashboard("EMP001", query);
        assertThat(d.get("scope").asText()).isEqualTo("OWN");
        assertThat(d.at("/sales/premiums/amount").asText()).isEqualTo("3150.00");
        assertThat(d.toString()).doesNotContain("EMP002").doesNotContain("2280.00");
    }

    // --- mes ---

    @Test
    void defaultMonthIsTheLastClosedMonthOfTheBusinessClock() throws Exception {
        JsonNode d = dashboard("DIR001", "");   // reloj fijo 2027-01-15 ⇒ último mes cerrado 2026-12
        assertThat(d.get("month").asText()).isEqualTo("2026-12");
        assertThat(d.get("defaultMonth").asText()).isEqualTo("2026-12");
        assertThat(d.get("availableMonths")).extracting(JsonNode::asText).containsExactly("2026-09", "2026-10", "2026-11", "2026-12");
        assertThat(d.at("/sales/premiums/amount").asText()).isEqualTo("300.00");
    }

    @Test
    void monthsWithoutDataOrMalformedAreRejectedWith400() throws Exception {
        String auth = rest.bearer("DIR001");
        for (String month : List.of("2026-08", "2027-01", "2026-13", "foo", "2026-1", "2026-10-01")) {
            var res = mvc.perform(get("/api/v1/dashboard?month=" + month).header("Authorization", auth)).andReturn().getResponse();
            assertThat(res.getStatus()).as(month).isEqualTo(400);
            assertThat(res.getContentType()).contains("application/problem+json");
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        assertThat(mvc.perform(get("/api/v1/dashboard")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // --- servicios reutilizables: reciben un Subject y aplican el alcance ellos mismos (los usará el chat en F4) ---

    @Test
    void queryServicesApplyTheScopeOfTheSubject() {
        var october = java.time.YearMonth.of(2026, 10);
        Subject emp1 = subject("EMP001", Role.EMPLOYEE);
        Subject acc = subject("ACC001", Role.ACCOUNTANT);
        Subject com = subject("COM001", Role.SALES_MANAGER);
        Subject dir = subject("DIR001", Role.MANAGER);
        assertThat(sales.summary(emp1, october).premiums().amount()).isEqualTo("3150.00");
        assertThat(sales.summary(com, october).premiums().amount()).isEqualTo("9680.00");
        assertThat(targets.compliance(acc, october)).isEmpty();
        assertThat(targets.compliance(emp1, october).orElseThrow().employees()).hasSize(1);
        assertThat(targets.compliance(dir, october).orElseThrow().employees()).hasSize(4);
        assertThat(expenses.monthTotal(acc, october)).hasValueSatisfying(m -> assertThat(m.amount()).isEqualTo("7250.00"));
        for (Subject s : List.of(emp1, com, dir)) {
            assertThat(expenses.monthTotal(s, october)).as("gastos solo para ACCOUNTANT: " + s.role()).isEmpty();
        }
    }

    @Test
    void salaryNeverAppearsInDashboardResponses() throws Exception {
        for (String code : List.of("DIR001", "ACC001", "COM001", "EMP001", "EMP002", "EMP003", "EMP004")) {
            for (String month : List.of("2026-09", "2026-10", "2026-11", "2026-12")) {
                assertThat(dashboard(code, "?month=" + month).toString()).as(code + " " + month)
                        .doesNotContainIgnoringCase("salary").doesNotContainIgnoringCase("salario");
            }
        }
    }

    // --- soporte ---

    private JsonNode dashboard(String code, String query) throws Exception {
        var res = mvc.perform(get("/api/v1/dashboard" + query).header("Authorization", rest.bearer(code))).andReturn().getResponse();
        assertThat(res.getStatus()).as(code + " " + query).isEqualTo(200);
        return mapper.readTree(res.getContentAsString());
    }

    private UUID userId(String code) {
        return jdbc.queryForObject("SELECT id FROM users WHERE employee_code = ?", UUID.class, code);
    }

    private Subject subject(String code, Role role) {
        return new Subject(userId(code), role, code);
    }

    private UUID policy(String code, UUID customer, String premium, LocalDate sale, LocalDate cancelledAt) {
        UUID id = UUID.randomUUID();
        BigDecimal p = new BigDecimal(premium);
        jdbc.update("INSERT INTO policies (id, policy_number, employee_id, customer_id, product, insurer, premium, status, sale_date, "
                        + "start_date, renewal_date, cancelled_at, insurer_commission_rate, insurer_commission_amount) "
                        + "VALUES (?, ?, ?, ?, 'AUTO', 'Aseguradora', ?, ?, ?, ?, ?, ?, 0.2000, ?)",
                id, "P-T" + seq.incrementAndGet(), userId(code), customer, p, cancelledAt == null ? "ACTIVE" : "CANCELLED", sale, sale,
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

    private void expense(String date, String amount, String status) {
        jdbc.update("INSERT INTO expenses (expense_number, expense_date, category, supplier, description, amount, status, ledger_account) "
                        + "VALUES (?, ?::date, 'RENT', 'Proveedor', 'Gasto de prueba', ?, ?, '629')",
                "E-T" + seq.incrementAndGet(), date, new BigDecimal(amount), status);
    }
}
