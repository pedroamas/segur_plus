package com.segurplus.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** No público. Solo expone un agregado mensual. */
interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.expenseDate between :from and :to and e.status = 'APPROVED'")
    BigDecimal approvedTotal(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
