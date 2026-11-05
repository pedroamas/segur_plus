package com.segurplus.auth;

import com.segurplus.auth.api.UserProfile;
import com.segurplus.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Perfil del usuario autenticado. La identidad (id) viene del {@link Subject}; nunca incluye salario. */
@Service
public class CurrentUserService {

    private final UserRepository users;

    CurrentUserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserProfile profile(Subject subject) {
        return users.findById(subject.userId()).filter(User::isActive)
                .map(u -> new UserProfile(u.getId(), u.getEmployeeCode(), u.getEmail(), u.getFullName(), u.getRole(),
                        u.getDepartment()))
                .orElseThrow(ApiException::unauthorized);
    }
}
