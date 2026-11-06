package com.segurplus.data;

import com.segurplus.auth.Subject;
import com.segurplus.data.TargetCompliance.EmployeeTarget;
import com.segurplus.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Objetivos y cumplimiento del mes dentro del alcance del {@link Subject}. ACCOUNTANT no tiene acceso (vacío). */
@Service
public class TargetQuery {

    private final SalesTargetRepository targets;
    private final PolicyRepository policies;
    private final SalesPersonRepository people;
    private final DataScopeResolver scopes;

    TargetQuery(SalesTargetRepository targets, PolicyRepository policies, SalesPersonRepository people, DataScopeResolver scopes) {
        this.targets = targets;
        this.policies = policies;
        this.people = people;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public Optional<TargetCompliance> compliance(Subject subject, YearMonth month) {
        DataScope scope = scopes.resolve(subject);
        if (scope.aggregateOnly()) {
            return Optional.empty();
        }
        Periods p = Periods.of(month);
        Map<UUID, BigDecimal> premiums = new HashMap<>();
        for (Object[] row : policies.salesByEmployee(p.from(), p.to(), scope.global(), scope.idsParam())) {
            premiums.put((UUID) row[0], (BigDecimal) row[2]);
        }
        Map<UUID, BigDecimal> targetByEmployee = new HashMap<>();
        for (Object[] row : targets.targets(p.from(), scope.global(), scope.idsParam())) {
            targetByEmployee.put((UUID) row[0], (BigDecimal) row[1]);
        }
        Map<UUID, String[]> identities = new HashMap<>();
        if (!targetByEmployee.isEmpty()) {
            for (Object[] row : people.identities(targetByEmployee.keySet())) {
                identities.put((UUID) row[0], new String[] {(String) row[1], (String) row[2]});
            }
        }
        BigDecimal totalTarget = BigDecimal.ZERO;
        BigDecimal totalPremiums = BigDecimal.ZERO;
        List<EmployeeTarget> rows = new java.util.ArrayList<>();
        for (var e : targetByEmployee.entrySet()) {
            BigDecimal sold = premiums.getOrDefault(e.getKey(), BigDecimal.ZERO);
            String[] who = identities.getOrDefault(e.getKey(), new String[] {"?", "?"});
            rows.add(new EmployeeTarget(who[0], who[1], Money.eur(e.getValue()), Money.eur(sold), percent(sold, e.getValue())));
            totalTarget = totalTarget.add(e.getValue());
            totalPremiums = totalPremiums.add(sold);
        }
        rows.sort(Comparator.comparing(EmployeeTarget::employeeCode));
        return Optional.of(new TargetCompliance(rows, Money.eur(totalTarget), Money.eur(totalPremiums), percent(totalPremiums, totalTarget)));
    }

    private static BigDecimal percent(BigDecimal sold, BigDecimal target) {
        if (target.signum() == 0) {
            return null;
        }
        return sold.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP);
    }
}
