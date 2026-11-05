package com.segurplus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.shared.ApiException;
import com.segurplus.shared.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API sin estado: todo exige JWT salvo login/refresh/logout, OpenAPI y health. Sin autenticación ⇒ 401 y sin permiso ⇒ 403,
 * ambos como Problem Details. CSRF desactivado: ninguna operación se autentica con cookie salvo refresh/logout, que son
 * POST con cookie {@code SameSite=Strict} y {@code Path} acotado (ADR 0007 §9).
 */
@Configuration
class SecurityConfig implements WebMvcConfigurer {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper, AuditPublisher audit)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> { })
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(mapper, req, res, HttpStatus.UNAUTHORIZED, ApiException.unauthorized()))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(mapper, req, res, HttpStatus.FORBIDDEN, ApiException.forbidden())))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(mapper, req, res, HttpStatus.UNAUTHORIZED, ApiException.unauthorized()))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(mapper, req, res, HttpStatus.FORBIDDEN, ApiException.forbidden())))
                .addFilterAfter(new IgnoredClientFieldsFilter(audit), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, HttpStatus status,
            ApiException ex) throws IOException {
        ProblemDetail pd = Problems.of(status, ex.code(), ex.getMessage(), req.getRequestURI());
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getWriter(), pd);
    }

    /** Inyecta el {@link Subject} del JWT validado como parámetro de controlador. Es la única forma de conocer al usuario. */
    @Override
    public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return Subject.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                    WebDataBinderFactory binderFactory) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth instanceof JwtAuthenticationToken jwt) {
                    return Subject.fromJwt(jwt.getToken());
                }
                throw ApiException.unauthorized();
            }
        });
    }
}
