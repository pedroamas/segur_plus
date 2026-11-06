package com.segurplus.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** No público (ver {@link PolicyRepository} sobre el alcance). */
interface CommissionRepository extends JpaRepository<Commission, UUID> {

    @Query("select coalesce(sum(c.amount), 0) from Commission c "
            + "where c.periodMonth = :month and c.kind = :kind and (:global = true or c.employeeId in :ids)")
    BigDecimal sumByKind(@Param("month") LocalDate month, @Param("kind") CommissionKind kind, @Param("global") boolean global,
            @Param("ids") Collection<UUID> ids);
}
