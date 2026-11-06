package com.segurplus.data;

import com.segurplus.auth.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Vista de solo lectura de {@code users}: código y nombre para el equipo comercial. Sin salario ni credenciales. */
@Entity
@Immutable
@Table(name = "users")
class SalesPerson {

    @Id
    private UUID id;
    @Column(name = "employee_code")
    private String employeeCode;
    @Column(name = "full_name")
    private String fullName;
    @Enumerated(EnumType.STRING)
    private Role role;
    private boolean active;

    protected SalesPerson() {
    }
}
