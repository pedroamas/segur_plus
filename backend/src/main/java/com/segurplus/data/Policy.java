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

/** Solo las columnas que se leen para cifras agregadas (lectura). */
@Entity
@Immutable
@Table(name = "policies")
class Policy {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private UUID employeeId;
    private BigDecimal premium;
    @Enumerated(EnumType.STRING)
    private PolicyStatus status;
    @Column(name = "sale_date")
    private LocalDate saleDate;

    protected Policy() {
    }
}
