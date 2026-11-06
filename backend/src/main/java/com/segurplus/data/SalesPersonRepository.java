package com.segurplus.data;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** No público: el resto de la aplicación usa los servicios de data. */
interface SalesPersonRepository extends JpaRepository<SalesPerson, UUID> {

    @Query("select p.id from SalesPerson p where p.role = com.segurplus.auth.Role.EMPLOYEE and p.active = true")
    List<UUID> activeEmployeeIds();

    @Query("select p.id, p.employeeCode, p.fullName from SalesPerson p where p.id in :ids")
    List<Object[]> identities(java.util.Collection<UUID> ids);
}
