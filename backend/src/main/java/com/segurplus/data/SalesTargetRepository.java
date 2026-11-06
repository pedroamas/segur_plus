package com.segurplus.data;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** No público (ver {@link PolicyRepository} sobre el alcance). */
interface SalesTargetRepository extends JpaRepository<SalesTarget, UUID> {

    @Query("select t.employeeId, t.targetAmount from SalesTarget t "
            + "where t.periodMonth = :month and (:global = true or t.employeeId in :ids)")
    List<Object[]> targets(@Param("month") LocalDate month, @Param("global") boolean global, @Param("ids") Collection<UUID> ids);
}
