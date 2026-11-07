package com.segurplus.auth;

import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.auth.api.UserOption;
import com.segurplus.shared.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Directorio mínimo de usuarios activos para elegir propietario al subir un documento. Solo MANAGER (acción ADMINISTER). */
@Service
public class UserDirectoryService {

    private final UserRepository users;
    private final AccessPolicyService policy;
    private final AuditPublisher audit;

    UserDirectoryService(UserRepository users, AccessPolicyService policy, AuditPublisher audit) {
        this.users = users;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserOption> listActive(Subject subject) {
        Decision decision = policy.decide(subject, Action.ADMINISTER, null);
        if (!decision.allowed()) {
            audit.publish(new AuditEvent(AuditAction.ACCESS_DENIED, subject.userId(), "USER", null, false, decision.reasonCode(),
                    AuditEvent.Channel.REST, Map.of("action", Action.ADMINISTER.name())));
            throw ApiException.forbidden();
        }
        return users.findByActiveTrueOrderByEmployeeCodeAsc().stream()
                .map(u -> new UserOption(u.getId(), u.getEmployeeCode(), u.getFullName(), u.getRole())).toList();
    }
}
