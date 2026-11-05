package com.segurplus.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.shared.Correlation;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inserta filas en {@code audit_logs} en una transacción propia (REQUIRES_NEW): un DENY queda registrado aunque la
 * petición termine en 404 y su transacción se revierta. {@code occurred_at} usa el {@code now()} de la BD (tiempo real);
 * {@code business_date} sale del {@link Clock} de negocio.
 */
@Service
public class AuditPublisher {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper mapper;

    AuditPublisher(JdbcClient jdbc, PlatformTransactionManager txManager, Clock clock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.mapper = mapper;
    }

    public void publish(AuditEvent e) {
        String details;
        try {
            details = mapper.writeValueAsString(e.details());
        } catch (JsonProcessingException ex) {
            details = "{}";
        }
        String detailsJson = details;
        LocalDate businessDate = LocalDate.now(clock);
        tx.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO audit_logs (business_date, user_id, action, resource_type, resource_id, decision,
                                        reason_code, channel, correlation_id, details)
                VALUES (:businessDate, :userId, :action, :resourceType, :resourceId, :decision,
                        :reasonCode, :channel, :correlationId, CAST(:details AS jsonb))
                """)
                .param("businessDate", businessDate)
                .param("userId", e.userId())
                .param("action", e.action().name())
                .param("resourceType", e.resourceType())
                .param("resourceId", e.resourceId())
                .param("decision", e.allowed() ? "ALLOW" : "DENY")
                .param("reasonCode", e.reasonCode())
                .param("channel", e.channel() == null ? null : e.channel().name())
                .param("correlationId", Correlation.current())
                .param("details", detailsJson)
                .update());
    }
}
