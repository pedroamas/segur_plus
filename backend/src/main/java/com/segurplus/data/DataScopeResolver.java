package com.segurplus.data;

import com.segurplus.auth.Subject;
import com.segurplus.shared.ApiException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ÚNICO sitio donde el rol se convierte en alcance de datos (ADR 0005). La identidad sale del {@link Subject} (JWT);
 * ningún servicio ni controlador acepta empleados o roles como parámetro.
 */
@Component
class DataScopeResolver {

    private final SalesPersonRepository people;

    DataScopeResolver(SalesPersonRepository people) {
        this.people = people;
    }

    DataScope resolve(Subject subject) {
        if (subject == null || subject.role() == null) {
            throw ApiException.forbidden();
        }
        return switch (subject.role()) {
            case MANAGER -> new DataScope(DataScope.Kind.GLOBAL, Set.of(), false);
            case ACCOUNTANT -> new DataScope(DataScope.Kind.GLOBAL, Set.of(), true);
            case SALES_MANAGER -> new DataScope(DataScope.Kind.TEAM, Set.copyOf(people.activeEmployeeIds()), false);
            case EMPLOYEE -> new DataScope(DataScope.Kind.OWN, Set.of(subject.userId()), false);
        };
    }
}
