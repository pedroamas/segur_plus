package com.segurplus.data;

import com.segurplus.auth.Subject;
import com.segurplus.shared.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ventas (pólizas) y primas del mes dentro del alcance del {@link Subject}. */
@Service
public class SalesQuery {

    private final PolicyRepository policies;
    private final DataScopeResolver scopes;

    SalesQuery(PolicyRepository policies, DataScopeResolver scopes) {
        this.policies = policies;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public SalesSummary summary(Subject subject, YearMonth month) {
        DataScope scope = scopes.resolve(subject);
        Periods p = Periods.of(month);
        Object[] row = policies.salesTotals(p.from(), p.to(), scope.global(), scope.idsParam()).get(0);
        return new SalesSummary(((Number) row[0]).longValue(), Money.eur((BigDecimal) row[1]));
    }

    /** Meses con datos (de la primera a la última venta): información global, sin cifras ni identidades. */
    @Transactional(readOnly = true)
    public List<YearMonth> availableMonths() {
        Object[] range = policies.saleDateRange().get(0);
        if (range[0] == null) {
            return List.of();
        }
        YearMonth first = YearMonth.from((java.time.LocalDate) range[0]);
        YearMonth last = YearMonth.from((java.time.LocalDate) range[1]);
        return first.atDay(1).datesUntil(last.atEndOfMonth().plusDays(1), java.time.Period.ofMonths(1)).map(YearMonth::from).toList();
    }
}
