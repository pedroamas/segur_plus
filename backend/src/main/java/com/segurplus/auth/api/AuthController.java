package com.segurplus.auth.api;

import com.segurplus.auth.SessionService;
import com.segurplus.auth.SessionService.Session;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
@SecurityRequirements
class AuthController {

    static final String REFRESH_COOKIE = "segurplus_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final SessionService sessions;

    AuthController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Devuelve el access JWT y fija la cookie de refresh (HttpOnly; Secure; SameSite=Strict).")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(sessions.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh con rotación",
            description = "Cada uso devuelve un refresh nuevo y revoca el anterior. Reutilizar uno rotado revoca toda la familia (401).")
    ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(sessions.refresh(refreshToken));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Revoca la familia del refresh recibido y borra la cookie. 204 siempre.")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        sessions.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private static ResponseEntity<TokenResponse> withRefreshCookie(Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), session.refreshMaxAge()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(session.accessToken(), "Bearer", session.expiresInSeconds()));
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path(COOKIE_PATH).maxAge(maxAge).build();
    }
}
