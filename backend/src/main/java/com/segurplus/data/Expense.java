package com.segurplus.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Solo se lee agregado (suma mensual para ACCOUNTANT); ninguna cifra individual sale de aquí. */
@Entity
@Immutable
@Table(name = "expenses")
class Expense {

    @Id
    private UUID id;
    @Column(name = "expense_date")
    private LocalDate expenseDate;
    private BigDecimal amount;
    private String status;

    protected Expense() {
    }
}
