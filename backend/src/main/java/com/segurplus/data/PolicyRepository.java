package com.segurplus.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * No público. El alcance se pasa como {@code global} + {@code ids} (nunca vacío: {@link DataScope#idsParam()}); lo calcula
 * únicamente {@link DataScopeResolver}.
 */
interface PolicyRepository extends JpaRepository<Policy, UUID> {

    @Query("select count(p), coalesce(sum(p.premium), 0) from Policy p "
            + "where p.saleDate between :from and :to and (:global = true or p.employeeId in :ids)")
    List<Object[]> salesTotals(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("global") boolean global,
            @Param("ids") Collection<UUID> ids);

    @Query("select p.employeeId, count(p), coalesce(sum(p.premium), 0) from Policy p "
            + "where p.saleDate between :from and :to and (:global = true or p.employeeId in :ids) group by p.employeeId")
    List<Object[]> salesByEmployee(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("global") boolean global,
            @Param("ids") Collection<UUID> ids);

    @Query("select p.status, count(p) from Policy p "
            + "where p.saleDate between :from and :to and (:global = true or p.employeeId in :ids) group by p.status")
    List<Object[]> countByStatus(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("global") boolean global,
            @Param("ids") Collection<UUID> ids);

    @Query("select min(p.saleDate), max(p.saleDate) from Policy p")
    List<Object[]> saleDateRange();
}
