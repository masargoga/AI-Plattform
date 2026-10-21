package com.agenticpilot.common;

import com.agenticpilot.user.AppUser;
import com.agenticpilot.user.AppUserRepository;
import com.agenticpilot.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The signed-in user, looked up from the security context. Services use it for ownership checks. */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public AppUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Not signed in");
        }
        return users.findByUsername(auth.getName()).orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }

    public boolean isAdmin() {
        return require().getRole() == Role.ADMIN;
    }

    /** Admins may edit everything; others only what they own or created. */
    public boolean canEdit(AppUser owner) {
        AppUser me = require();
        return me.getRole() == Role.ADMIN || me.getId().equals(owner.getId());
    }

    public void requireEdit(AppUser owner) {
        if (!canEdit(owner)) {
            throw new AccessDeniedException("Only the owner or an Admin may change this");
        }
    }
}
