package com.segurplus.data;

import com.segurplus.auth.Subject;
import com.segurplus.shared.Money;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gastos del mes: SOLO el total agregado y SOLO para ACCOUNTANT (decisión de F3 para el dashboard; el chat no tiene
 * herramienta de gastos, ADR 0005). Cualquier otro rol recibe vacío.
 */
@Service
public class ExpenseQuery {

    private final ExpenseRepository expenses;
    private final DataScopeResolver scopes;

    ExpenseQuery(ExpenseRepository expenses, DataScopeResolver scopes) {
        this.expenses = expenses;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public Optional<Money> monthTotal(Subject subject, YearMonth month) {
        // aggregateOnly identifica al contable: el único alcance al que se le permite este agregado.
        if (!scopes.resolve(subject).aggregateOnly()) {
            return Optional.empty();
        }
        Periods p = Periods.of(month);
        return Optional.of(Money.eur(expenses.approvedTotal(p.from(), p.to())));
    }
}
