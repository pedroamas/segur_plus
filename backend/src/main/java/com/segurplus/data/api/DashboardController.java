package com.segurplus.data.api;

import com.segurplus.auth.Subject;
import com.segurplus.data.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** El alcance sale del {@link Subject} (JWT): el único parámetro es el mes. Cualquier parámetro de identidad se ignora. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "dashboard")
class DashboardController {

    private final DashboardService service;

    DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Cifras del mes según el rol del token",
            description = "month=YYYY-MM (solo meses con datos; 400 en otro caso). Por defecto, el último mes cerrado según el reloj de negocio.")
    DashboardResponse get(Subject subject, @RequestParam(required = false) String month) {
        return service.dashboard(subject, month);
    }
}
