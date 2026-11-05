package com.segurplus.auth;

import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.shared.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login, refresh con rotación, detección de reutilización y logout (ADR 0007).
 * El refresh se ejecuta en una transacción programática que devuelve un resultado (no lanza): así la revocación de la
 * familia ante reutilización se confirma aunque la petición termine en 401.
 */
@Service
public class SessionService {

    /** Resultado de emitir una sesión: access JWT y refresh opaco (este último solo viaja en la cookie). */
    public record Session(String accessToken, long expiresInSeconds, String refreshToken, Duration refreshMaxAge) {
    }

    private sealed interface RefreshOutcome permits Rotated, Rejected {
    }

    private record Rotated(Session session) implements RefreshOutcome {
    }

    private record Rejected() implements RefreshOutcome {
    }

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditPublisher audit;
    private final AuthProperties props;
    private final TokenTime time;
    private final TransactionTemplate tx;
    /** Hash válido de una contraseña inexistente: iguala el coste de bcrypt cuando el usuario no existe. */
    private final String dummyHash;

    SessionService(UserRepository users, RefreshTokenRepository refreshTokens, AccessTokenService accessTokens,
            PasswordEncoder passwordEncoder, AuditPublisher audit, AuthProperties props, TokenTime time,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.props = props;
        this.time = time;
        this.tx = new TransactionTemplate(txManager);
        this.dummyHash = passwordEncoder.encode(TokenHashing.newOpaqueToken());
    }

    public Session login(String username, String password) {
        Optional<User> found = (username == null || password == null) ? Optional.empty()
                : users.findByEmployeeCodeIgnoreCaseOrEmailIgnoreCase(username.trim(), username.trim());
        boolean passwordOk = passwordEncoder.matches(password == null ? "" : password,
                found.map(User::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !found.get().isActive() || !passwordOk) {
            // No se registra el usuario intentado (podría ser una contraseña pegada en el campo equivocado).
            audit.publish(AuditEvent.rest(AuditAction.LOGIN_FAILED, found.map(User::getId).orElse(null), false, "BAD_CREDENTIALS"));
            throw ApiException.unauthorized();
        }
        User user = found.get();
        Session session = tx.execute(status -> issueSession(user, UUID.randomUUID()));
        audit.publish(AuditEvent.rest(AuditAction.LOGIN, user.getId(), true, "OK"));
        return session;
    }

    public Session refresh(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            audit.publish(AuditEvent.rest(AuditAction.TOKEN_REFRESH, null, false, "MISSING"));
            throw ApiException.unauthorized();
        }
        String hash = TokenHashing.sha256Hex(presentedToken);
        RefreshOutcome outcome = tx.execute(status -> rotate(hash));
        if (outcome instanceof Rotated rotated) {
            return rotated.session();
        }
        throw ApiException.unauthorized();
    }

    /** Revoca la familia del refresh recibido. Nunca revela si el token existía. */
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        String hash = TokenHashing.sha256Hex(presentedToken);
        UUID userId = tx.execute(status -> refreshTokens.findByTokenHash(hash).map(token -> {
            refreshTokens.revokeFamily(token.getFamilyId(), time.now());
            return token.getUserId();
        }).orElse(null));
        if (userId != null) {
            audit.publish(AuditEvent.rest(AuditAction.LOGOUT, userId, true, "OK"));
        }
    }

    private RefreshOutcome rotate(String hash) {
        Optional<RefreshToken> found = refreshTokens.findByTokenHash(hash);
        if (found.isEmpty()) {
            audit.publish(AuditEvent.rest(AuditAction.TOKEN_REFRESH, null, false, "UNKNOWN"));
            return new Rejected();
        }
        RefreshToken current = found.get();
        Instant now = time.now();
        if (current.getRevokedAt() != null) {
            // Reutilización de un token ya rotado o revocado: se revoca TODA la familia (incluido el más reciente).
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            audit.publish(AuditEvent.rest(AuditAction.TOKEN_REUSE_DETECTED, current.getUserId(), false, "REUSE"));
            return new Rejected();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            audit.publish(AuditEvent.rest(AuditAction.TOKEN_REFRESH, current.getUserId(), false, "EXPIRED"));
            return new Rejected();
        }
        Optional<User> user = users.findById(current.getUserId()).filter(User::isActive);
        if (user.isEmpty()) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            audit.publish(AuditEvent.rest(AuditAction.TOKEN_REFRESH, current.getUserId(), false, "USER_INACTIVE"));
            return new Rejected();
        }
        // Se relee el rol de la BD: un cambio de rol se aplica como mucho en el siguiente refresh.
        Session next = issueSession(user.get(), current.getFamilyId(), current, now);
        audit.publish(AuditEvent.rest(AuditAction.TOKEN_REFRESH, current.getUserId(), true, "ROTATED"));
        return new Rotated(next);
    }

    private Session issueSession(User user, UUID familyId) {
        return issueSession(user, familyId, null, time.now());
    }

    private Session issueSession(User user, UUID familyId, RefreshToken previous, Instant now) {
        String raw = TokenHashing.newOpaqueToken();
        RefreshToken fresh = new RefreshToken(user.getId(), TokenHashing.sha256Hex(raw), familyId, now,
                now.plus(props.refreshTokenTtl()));
        refreshTokens.saveAndFlush(fresh);
        if (previous != null) {
            previous.rotateTo(fresh.getId(), now);
        }
        return new Session(accessTokens.issue(user), accessTokens.expiresInSeconds(), raw, props.refreshTokenTtl());
    }
}
