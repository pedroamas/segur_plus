package com.segurplus.auth;

import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Los campos de identidad que envía el cliente ({@code employee_id}, {@code role}, {@code assistantId}, {@code datasetId})
 * se IGNORAN: ningún controlador los lee. Aquí solo se deja constancia en la auditoría (nombres, nunca valores).
 * Solo se inspecciona la query string; los cuerpos JSON ignoran los campos desconocidos por configuración.
 */
/** No es un bean: se añade solo a la cadena de seguridad (tras autenticar) para conocer al usuario. */
class IgnoredClientFieldsFilter extends OncePerRequestFilter {

    private static final Set<String> IDENTITY_FIELDS = Set.of("employee_id", "employeeid", "role", "assistantid", "datasetid", "user_id", "userid");

    private final AuditPublisher audit;

    IgnoredClientFieldsFilter(AuditPublisher audit) {
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String query = request.getQueryString();
        if (query != null) {
            Set<String> ignored = new TreeSet<>();
            UriComponentsBuilder.fromUriString("?" + query).build().getQueryParams().keySet().forEach(name -> {
                if (IDENTITY_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
                    ignored.add(name.toLowerCase(Locale.ROOT));
                }
            });
            if (!ignored.isEmpty()) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                var userId = auth instanceof JwtAuthenticationToken jwt ? Subject.fromJwt(jwt.getToken()).userId() : null;
                audit.publish(AuditEvent.rest(AuditAction.IGNORED_CLIENT_FIELD, userId, true, "CLIENT_IDENTITY_FIELD_IGNORED")
                        .withDetails(Map.of("fields", ignored.stream().toList(), "path", request.getRequestURI())));
            }
        }
        chain.doFilter(request, response);
    }
}
