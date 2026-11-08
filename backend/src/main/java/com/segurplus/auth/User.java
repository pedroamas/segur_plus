package com.segurplus.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Solo las columnas que la aplicación lee. {@code monthly_salary} NO está mapeada a propósito: ningún DTO,
 * endpoint ni herramienta puede exponerla (solo la toca el generador de nóminas de F2 por SQL).
 */
@Entity
@Table(name = "users")
class User {

    @Id
    private UUID id;
    @Column(name = "employee_code")
    private String employeeCode;
    private String email;
    @Column(name = "password_hash")
    private String passwordHash;
    @Column(name = "full_name")
    private String fullName;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String department;
    private boolean active;
    @Column(name = "dify_user_ref")
    private String difyUserRef;

    protected User() {
    }

    UUID getId() { return id; }
    String getEmployeeCode() { return employeeCode; }
    String getEmail() { return email; }
    String getPasswordHash() { return passwordHash; }
    String getFullName() { return fullName; }
    Role getRole() { return role; }
    String getDepartment() { return department; }
    boolean isActive() { return active; }
    String getDifyUserRef() {
        return difyUserRef;
    }
}
