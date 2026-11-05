package com.segurplus.auth.api;

import com.segurplus.auth.CurrentUserService;
import com.segurplus.auth.Subject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users")
class UserController {

    private final CurrentUserService currentUser;

    UserController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    @Operation(summary = "Usuario autenticado", description = "La identidad sale solo del token; no admite parámetros de identidad.")
    UserProfile me(Subject subject) {
        return currentUser.profile(subject);
    }
}
