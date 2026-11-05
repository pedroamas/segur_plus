package com.segurplus.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tabla de verdad de {@code decide} (ADR 0002). Las otras dos formas (Specification, lote) se prueban contra la BD en la matriz. */
class AccessPolicyServiceTest {

    private final AccessPolicyService policy = new AccessPolicyService(null);

    private static Subject subject(Role role) {
        return new Subject(UUID.randomUUID(), role, "X001");
    }

    @Test
    void roleInAllowedRolesAllowsReadAndDownload() {
        ResourceAttributes doc = new ResourceAttributes(List.of("MANAGER", "ACCOUNTANT"), null, false);
        for (Action a : List.of(Action.READ, Action.DOWNLOAD)) {
            assertThat(policy.decide(subject(Role.ACCOUNTANT), a, doc)).isEqualTo(Decision.allow("ROLE_ALLOWED"));
            assertThat(policy.decide(subject(Role.EMPLOYEE), a, doc)).isEqualTo(Decision.deny("NOT_ALLOWED"));
        }
    }

    @Test
    void ownerIsAllowedEvenIfRoleIsNot() {
        Subject owner = subject(Role.EMPLOYEE);
        ResourceAttributes doc = new ResourceAttributes(List.of("MANAGER"), owner.userId(), false);
        assertThat(policy.decide(owner, Action.READ, doc)).isEqualTo(Decision.allow("OWNER"));
        assertThat(policy.decide(subject(Role.EMPLOYEE), Action.READ, doc).allowed()).isFalse();
    }

    @Test
    void deletedResourceIsDeniedEvenForManagerAndOwner() {
        Subject manager = subject(Role.MANAGER);
        ResourceAttributes doc = new ResourceAttributes(List.of("MANAGER"), manager.userId(), true);
        assertThat(policy.decide(manager, Action.READ, doc)).isEqualTo(Decision.deny("RESOURCE_DELETED"));
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CREATE", "UPDATE", "DELETE", "ADMINISTER"})
    void onlyManagerWrites(Action action) {
        ResourceAttributes doc = new ResourceAttributes(List.of("MANAGER", "EMPLOYEE"), null, false);
        assertThat(policy.decide(subject(Role.MANAGER), action, doc).allowed()).isTrue();
        for (Role r : List.of(Role.ACCOUNTANT, Role.SALES_MANAGER, Role.EMPLOYEE)) {
            assertThat(policy.decide(subject(r), action, doc).allowed()).as("%s %s", r, action).isFalse();
        }
    }

    @Test
    void denyByDefaultOnMissingInformation() {
        assertThat(policy.decide(null, Action.READ, new ResourceAttributes(List.of("MANAGER"), null, false)).allowed()).isFalse();
        assertThat(policy.decide(subject(Role.MANAGER), Action.READ, null).allowed()).isFalse();
        assertThat(policy.decide(subject(Role.MANAGER), null, null).allowed()).isFalse();
        // Roles desconocidos en allowedRoles no conceden nada.
        assertThat(policy.decide(subject(Role.EMPLOYEE), Action.READ, new ResourceAttributes(List.of("ADMIN", "*"), null, false)).allowed()).isFalse();
        assertThat(policy.decide(subject(Role.EMPLOYEE), Action.READ, new ResourceAttributes(null, null, false)).allowed()).isFalse();
    }
}
