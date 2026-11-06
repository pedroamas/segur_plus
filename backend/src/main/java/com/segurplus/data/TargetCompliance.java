package com.segurplus.data;

import com.segurplus.shared.Money;
import java.math.BigDecimal;
import java.util.List;

/** Cumplimiento de objetivos del mes. {@code percent} = primas ÷ objetivo × 100 con 1 decimal (null si no hay objetivo). */
public record TargetCompliance(List<EmployeeTarget> employees, Money target, Money premiums, BigDecimal percent) {

    public record EmployeeTarget(String employeeCode, String fullName, Money target, Money premiums, BigDecimal percent) {
    }
}
