package com.segurplus.auth.api;

import com.segurplus.auth.CurrentUserService;
import com.segurplus.auth.Subject;
import com.segurplus.auth.UserDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users")
class UserController {

    private final CurrentUserService currentUser;
    private final UserDirectoryService directory;

    UserController(CurrentUserService currentUser, UserDirectoryService directory) {
        this.currentUser = currentUser;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Usuarios activos (solo MANAGER; solo id, código, nombre y rol)")
    List<UserOption> list(Subject subject) {
        return directory.listActive(subject);
    }

    @GetMapping("/me")
    @Operation(summary = "Usuario autenticado", description = "La identidad sale solo del token; no admite parámetros de identidad.")
    UserProfile me(Subject subject) {
        return currentUser.profile(subject);
    }
}
