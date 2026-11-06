package com.segurplus.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Comisión del empleado: {@code ACCRUAL} positiva; {@code CLAWBACK} negativa en el mes de la cancelación. */
@Entity
@Immutable
@Table(name = "commissions")
class Commission {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private UUID employeeId;
    @Enumerated(EnumType.STRING)
    private CommissionKind kind;
    @Column(name = "period_month")
    private LocalDate periodMonth;
    private BigDecimal amount;

    protected Commission() {
    }
}
