package com.segurplus.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, UUID> {

    List<User> findByActiveTrueOrderByEmployeeCodeAsc();

    Optional<User> findByEmployeeCodeIgnoreCaseOrEmailIgnoreCase(String employeeCode, String email);
}
