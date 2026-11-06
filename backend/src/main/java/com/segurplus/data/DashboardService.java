package com.segurplus.data;

import com.segurplus.auth.Subject;
import com.segurplus.data.api.DashboardResponse;
import com.segurplus.shared.ApiException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Compone las cifras del dashboard con los servicios de consulta; no contiene lógica de alcance (la aplican ellos). */
@Service
public class DashboardService {

    private final SalesQuery sales;
    private final CommissionQuery commissions;
    private final PolicyQuery policies;
    private final TargetQuery targets;
    private final ExpenseQuery expenses;
    private final DataScopeResolver scopes;
    private final Clock clock;

    DashboardService(SalesQuery sales, CommissionQuery commissions, PolicyQuery policies, TargetQuery targets,
            ExpenseQuery expenses, DataScopeResolver scopes, Clock clock) {
        this.sales = sales;
        this.commissions = commissions;
        this.policies = policies;
        this.targets = targets;
        this.expenses = expenses;
        this.scopes = scopes;
        this.clock = clock;
    }

    /** @param requestedMonth {@code YYYY-MM} o null (por defecto: último mes cerrado según el Clock de negocio). */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(Subject subject, String requestedMonth) {
        List<YearMonth> available = sales.availableMonths();
        YearMonth defaultMonth = defaultMonth(available);
        YearMonth month = requestedMonth == null || requestedMonth.isBlank() ? defaultMonth : parse(requestedMonth, available);
        return new DashboardResponse(month.toString(), defaultMonth.toString(), available.stream().map(YearMonth::toString).toList(),
                scopes.resolve(subject).kind().name(), sales.summary(subject, month), commissions.summary(subject, month),
                policies.byStatus(subject, month), targets.compliance(subject, month).orElse(null),
                expenses.monthTotal(subject, month).orElse(null));
    }

    private YearMonth defaultMonth(List<YearMonth> available) {
        YearMonth lastClosed = YearMonth.now(clock).minusMonths(1);
        if (available.isEmpty()) {
            return lastClosed;
        }
        YearMonth latest = available.get(available.size() - 1);
        YearMonth first = available.get(0);
        return lastClosed.isAfter(latest) ? latest : lastClosed.isBefore(first) ? first : lastClosed;
    }

    private static YearMonth parse(String value, List<YearMonth> available) {
        YearMonth month;
        try {
            month = YearMonth.parse(value);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Parameter 'month' must have the format YYYY-MM.");
        }
        if (!available.contains(month)) {
            throw ApiException.badRequest(available.isEmpty() ? "There is no data for any month."
                    : "Month has no data. Available months: " + available.get(0) + " to " + available.get(available.size() - 1) + ".");
        }
        return month;
    }
}
