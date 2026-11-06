package com.segurplus.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * ÚNICO punto de decisión de autorización (ADR 0002). Deny by default.
 * <pre>
 * READ/DOWNLOAD  ⇔  no borrado  AND  (rol ∈ allowed_roles  OR  owner_id = usuario)
 * CREATE/UPDATE/DELETE/ADMINISTER  ⇔  rol = MANAGER
 * </pre>
 * La misma regla se ofrece en tres formas que deben coincidir: {@link #decide} (un recurso ya cargado),
 * {@link #toSpecification} (listados y detalle: el filtro va en la consulta, no en memoria) y {@link #authorizeBatch}
 * (re-autorización de lotes de IDs, p. ej. fragmentos de Dify en F4).
 */
@Service
public class AccessPolicyService {

    /** Nombres de atributo JPA que debe tener una entidad protegida por {@link #toSpecification}. */
    public static final String ATTR_ID = "id";
    public static final String ATTR_DELETED_AT = "deletedAt";
    public static final String ATTR_OWNER_ID = "ownerId";
    public static final String ATTR_ALLOWED_ROLES = "allowedRoles";

    private final EntityManager em;

    AccessPolicyService(EntityManager em) {
        this.em = em;
    }

    public Decision decide(Subject subject, Action action, ResourceAttributes resource) {
        if (subject == null || subject.role() == null || subject.userId() == null || action == null) {
            return Decision.deny("NO_SUBJECT");
        }
        if (!action.isRead()) {
            return subject.role() == Role.MANAGER ? Decision.allow("MANAGER_WRITE") : Decision.deny("MANAGER_ONLY");
        }
        if (resource == null) {
            return Decision.deny("RESOURCE_MISSING");
        }
        if (resource.deleted()) {
            return Decision.deny("RESOURCE_DELETED");
        }
        if (resource.allowedRoles() != null && resource.allowedRoles().contains(subject.role().name())) {
            return Decision.allow("ROLE_ALLOWED");
        }
        if (resource.ownerId() != null && resource.ownerId().equals(subject.userId())) {
            return Decision.allow("OWNER");
        }
        return Decision.deny("NOT_ALLOWED");
    }

    /** Misma regla que {@link #decide}, como predicado de consulta. Para acciones de escritura: todo o nada según el rol. */
    public <T> Specification<T> toSpecification(Subject subject, Action action) {
        if (subject == null || subject.role() == null || subject.userId() == null || action == null) {
            return (root, query, cb) -> cb.disjunction();
        }
        if (!action.isRead()) {
            return (root, query, cb) -> subject.role() == Role.MANAGER
                    ? cb.isNull(root.get(ATTR_DELETED_AT)) : cb.disjunction();
        }
        return (root, query, cb) -> {
            Predicate roleAllowed = cb.isTrue(cb.function("array_contains", Boolean.class,
                    root.get(ATTR_ALLOWED_ROLES), cb.literal(subject.role().name())));
            Predicate owner = cb.equal(root.get(ATTR_OWNER_ID), subject.userId());
            return cb.and(cb.isNull(root.get(ATTR_DELETED_AT)), cb.or(roleAllowed, owner));
        };
    }

    /**
     * Devuelve, de los IDs dados, solo los que {@code subject} puede {@code action}-ar: una única consulta con la
     * {@link #toSpecification Specification}. IDs inexistentes, borrados o no autorizados se omiten (fallo cerrado).
     */
    public <T> Set<UUID> authorizeBatch(Subject subject, Action action, Collection<UUID> ids, Class<T> entityType) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UUID> query = cb.createQuery(UUID.class);
        Root<T> root = query.from(entityType);
        Specification<T> spec = this.<T>toSpecification(subject, action);
        Predicate policy = spec.toPredicate(root, query, cb);
        query.select(root.get(ATTR_ID)).where(cb.and(root.get(ATTR_ID).in(ids), policy));
        return new HashSet<>(em.createQuery(query).getResultList());
    }
}
