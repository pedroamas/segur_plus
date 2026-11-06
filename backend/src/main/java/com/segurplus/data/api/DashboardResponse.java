package com.segurplus.data.api;

import com.segurplus.data.CommissionSummary;
import com.segurplus.data.PolicyStatusCounts;
import com.segurplus.data.SalesSummary;
import com.segurplus.data.TargetCompliance;
import com.segurplus.shared.Money;
import java.util.List;

/**
 * Cifras del dashboard según el rol del token. {@code scope}: OWN, TEAM o GLOBAL. {@code targets} es null para ACCOUNTANT
 * (sin cumplimiento por empleado) y {@code expenses} es null salvo para ACCOUNTANT.
 */
public record DashboardResponse(String month, String defaultMonth, List<String> availableMonths, String scope, SalesSummary sales,
                                CommissionSummary commissions, PolicyStatusCounts policiesByStatus, TargetCompliance targets,
                                Money expenses) {
}
