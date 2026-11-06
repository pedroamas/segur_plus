package com.segurplus.data;

import com.segurplus.auth.Subject;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pólizas por estado (vendidas en el mes) dentro del alcance del {@link Subject}. */
@Service
public class PolicyQuery {

    private final PolicyRepository policies;
    private final DataScopeResolver scopes;

    PolicyQuery(PolicyRepository policies, DataScopeResolver scopes) {
        this.policies = policies;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public PolicyStatusCounts byStatus(Subject subject, YearMonth month) {
        DataScope scope = scopes.resolve(subject);
        Periods p = Periods.of(month);
        Map<PolicyStatus, Long> counts = new EnumMap<>(PolicyStatus.class);
        for (Object[] row : policies.countByStatus(p.from(), p.to(), scope.global(), scope.idsParam())) {
            counts.put((PolicyStatus) row[0], ((Number) row[1]).longValue());
        }
        return new PolicyStatusCounts(counts.getOrDefault(PolicyStatus.ACTIVE, 0L), counts.getOrDefault(PolicyStatus.CANCELLED, 0L),
                counts.getOrDefault(PolicyStatus.EXPIRED, 0L));
    }
}
