package com.segurplus.ai;

import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.auth.Subject;
import com.segurplus.data.CommissionQuery;
import com.segurplus.data.CommissionSummary;
import com.segurplus.data.PolicyQuery;
import com.segurplus.data.PolicyStatusCounts;
import com.segurplus.data.SalesQuery;
import com.segurplus.data.SalesSummary;
import com.segurplus.data.TargetCompliance;
import com.segurplus.data.TargetQuery;
import com.segurplus.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Las 4 herramientas tipadas (ADR 0005). Son fachadas de los servicios de {@code data} de F3 (los mismos que alimentan el
 * dashboard): ninguna lógica de alcance propia, ningún SQL, y {@code employee_id}/{@code role} no existen como parámetro. El
 * alcance sale del {@link Subject}. Los parámetros llegan ya validados por {@link ToolCall}.
 */
@Service
class ToolExecutor {

    static final Set<String> DATA_TOOLS = Set.of("get_sales_summary", "get_commissions", "get_policies_by_status", "get_targets");
    static final Set<String> ALLOWED_PARAMS = Set.of("period", "status");
    static final Set<String> STATUSES = Set.of("ACTIVE", "CANCELLED", "EXPIRED");
    private static final Pattern MONTH = Pattern.compile("^(?:MONTH:)?(\\d{4})-(0[1-9]|1[0-2])$");
    static final String DEFAULT_PERIOD = "LAST_MONTH";

    private final SalesQuery sales;
    private final CommissionQuery commissions;
    private final PolicyQuery policies;
    private final TargetQuery targets;
    private final AuditPublisher audit;
    private final Clock clock;

    ToolExecutor(SalesQuery sales, CommissionQuery commissions, PolicyQuery policies, TargetQuery targets, AuditPublisher audit,
            Clock clock) {
        this.sales = sales;
        this.commissions = commissions;
        this.policies = policies;
        this.targets = targets;
        this.audit = audit;
        this.clock = clock;
    }

    /** Resuelve un periodo del catálogo con el reloj de negocio (ADR 0006). {@code null} si no es válido. */
    List<YearMonth> resolvePeriod(String period) {
        YearMonth now = YearMonth.now(clock);
        String p = period == null ? DEFAULT_PERIOD : period.strip().toUpperCase();
        switch (p) {
            case "THIS_MONTH":
                return List.of(now);
            case "LAST_MONTH":
                return List.of(now.minusMonths(1));
            case "YEAR_TO_DATE":
                List<YearMonth> months = new ArrayList<>();
                for (int m = 1; m <= now.getMonthValue(); m++) {
                    months.add(YearMonth.of(now.getYear(), m));
                }
                return months;
            default:
                Matcher m = MONTH.matcher(p);
                return m.matches() ? List.of(YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))) : null;
        }
    }

    ToolResult execute(Subject subject, ToolCall call) {
        String period = call.params().getOrDefault("period", DEFAULT_PERIOD);
        List<YearMonth> months = resolvePeriod(period);
        Map<String, String> params = new LinkedHashMap<>(call.params());
        params.putIfAbsent("period", DEFAULT_PERIOD);
        String asOf = LocalDate.now(clock).toString();
        ToolResult result;
        if (months == null) {
            result = new ToolResult(call.name(), params, asOf, Money.EUR, List.of(), Map.of(), "Invalid period.");
        } else {
            result = switch (call.name()) {
                case "get_sales_summary" -> salesSummary(subject, call, params, months, asOf);
                case "get_commissions" -> commissions(subject, call, params, months, asOf);
                case "get_policies_by_status" -> policiesByStatus(subject, call, params, months, asOf);
                case "get_targets" -> targets(subject, call, params, months, asOf);
                default -> new ToolResult(call.name(), params, asOf, Money.EUR, List.of(), Map.of(), "Unknown tool.");
            };
        }
        audit.publish(new AuditEvent(AuditAction.TOOL_CALL, subject.userId(), "TOOL", call.name(), true,
                result.note() == null ? "OK" : "NO_DATA", AuditEvent.Channel.AI_TOOL,
                Map.of("period", params.get("period"), "rows", result.rows().size())));
        return result;
    }

    private ToolResult salesSummary(Subject s, ToolCall call, Map<String, String> params, List<YearMonth> months, String asOf) {
        List<Map<String, Object>> rows = new ArrayList<>();
        long count = 0;
        BigDecimal premiums = BigDecimal.ZERO;
        for (YearMonth m : months) {
            SalesSummary r = sales.summary(s, m);
            rows.add(row("month", m.toString(), "policies", r.policyCount(), "premiums", r.premiums().amount()));
            count += r.policyCount();
            premiums = premiums.add(new BigDecimal(r.premiums().amount()));
        }
        return new ToolResult(call.name(), params, asOf, Money.EUR, rows,
                Map.of("policies", count, "premiums", Money.eur(premiums).amount()), null);
    }

    private ToolResult commissions(Subject s, ToolCall call, Map<String, String> params, List<YearMonth> months, String asOf) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal accrued = BigDecimal.ZERO;
        BigDecimal clawbacks = BigDecimal.ZERO;
        for (YearMonth m : months) {
            CommissionSummary r = commissions.summary(s, m);
            rows.add(row("month", m.toString(), "accrued", r.accrued().amount(), "clawbacks", r.clawbacks().amount(), "net", r.net().amount()));
            accrued = accrued.add(new BigDecimal(r.accrued().amount()));
            clawbacks = clawbacks.add(new BigDecimal(r.clawbacks().amount()));
        }
        return new ToolResult(call.name(), params, asOf, Money.EUR, rows,
                Map.of("accrued", Money.eur(accrued).amount(), "clawbacks", Money.eur(clawbacks).amount(),
                        "net", Money.eur(accrued.add(clawbacks)).amount()), null);
    }

    private ToolResult policiesByStatus(Subject s, ToolCall call, Map<String, String> params, List<YearMonth> months, String asOf) {
        String only = call.params().get("status");
        List<Map<String, Object>> rows = new ArrayList<>();
        long active = 0;
        long cancelled = 0;
        long expired = 0;
        for (YearMonth m : months) {
            PolicyStatusCounts r = policies.byStatus(s, m);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m.toString());
            if (only == null || only.equals("ACTIVE")) {
                row.put("active", r.active());
            }
            if (only == null || only.equals("CANCELLED")) {
                row.put("cancelled", r.cancelled());
            }
            if (only == null || only.equals("EXPIRED")) {
                row.put("expired", r.expired());
            }
            rows.add(row);
            active += r.active();
            cancelled += r.cancelled();
            expired += r.expired();
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        if (only == null || only.equals("ACTIVE")) {
            totals.put("active", active);
        }
        if (only == null || only.equals("CANCELLED")) {
            totals.put("cancelled", cancelled);
        }
        if (only == null || only.equals("EXPIRED")) {
            totals.put("expired", expired);
        }
        return new ToolResult(call.name(), params, asOf, Money.EUR, rows, totals, null);
    }

    private ToolResult targets(Subject s, ToolCall call, Map<String, String> params, List<YearMonth> months, String asOf) {
        if (months.size() != 1) {
            return new ToolResult(call.name(), params, asOf, Money.EUR, List.of(), Map.of(), "Targets are available for a single month only.");
        }
        Optional<TargetCompliance> compliance = targets.compliance(s, months.get(0));
        if (compliance.isEmpty()) {
            return new ToolResult(call.name(), params, asOf, Money.EUR, List.of(), Map.of(), "No data in your scope.");
        }
        TargetCompliance c = compliance.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : c.employees()) {
            rows.add(row("employee", e.employeeCode(), "name", e.fullName(), "target", e.target().amount(), "premiums", e.premiums().amount(),
                    "compliancePercent", e.percent() == null ? null : e.percent().toPlainString()));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("target", c.target().amount());
        totals.put("premiums", c.premiums().amount());
        totals.put("compliancePercent", c.percent() == null ? null : c.percent().toPlainString());
        return new ToolResult(call.name(), params, asOf, Money.EUR, rows, totals, null);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
