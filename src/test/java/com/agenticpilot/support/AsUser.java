package com.agenticpilot.support;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Signs a seeded user in for the current thread, as the web layer would after login. */
public final class AsUser {

    private AsUser() {
    }

    public static void signIn(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    public static void owner() { signIn("owner", "AGENT_OWNER"); }
    public static void admin() { signIn("admin", "ADMIN"); }
    public static void admin2() { signIn("admin2", "ADMIN"); }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
