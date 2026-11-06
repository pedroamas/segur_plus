package com.segurplus.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "sales_targets")
class SalesTarget {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private UUID employeeId;
    @Column(name = "period_month")
    private LocalDate periodMonth;
    @Column(name = "target_amount")
    private BigDecimal targetAmount;

    protected SalesTarget() {
    }
}
