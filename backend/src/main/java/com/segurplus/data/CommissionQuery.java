package com.segurplus.data;

import com.segurplus.auth.Subject;
import com.segurplus.shared.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Comisiones de empleado del mes dentro del alcance del {@link Subject}. */
@Service
public class CommissionQuery {

    private final CommissionRepository commissions;
    private final DataScopeResolver scopes;

    CommissionQuery(CommissionRepository commissions, DataScopeResolver scopes) {
        this.commissions = commissions;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public CommissionSummary summary(Subject subject, YearMonth month) {
        DataScope scope = scopes.resolve(subject);
        var first = month.atDay(1);
        BigDecimal accrued = commissions.sumByKind(first, CommissionKind.ACCRUAL, scope.global(), scope.idsParam());
        BigDecimal clawbacks = commissions.sumByKind(first, CommissionKind.CLAWBACK, scope.global(), scope.idsParam());
        return new CommissionSummary(Money.eur(accrued), Money.eur(clawbacks), Money.eur(accrued.add(clawbacks)));
    }
}
