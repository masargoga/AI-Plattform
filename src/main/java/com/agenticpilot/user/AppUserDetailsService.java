package com.agenticpilot.user;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final LoginAttempts attempts;

    public AppUserDetailsService(AppUserRepository users, LoginAttempts attempts) {
        this.users = users;
        this.attempts = attempts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(user.getRole().authority())
                .disabled(!user.isActive())
                .accountLocked(attempts.isLocked(username))
                .build();
    }
}
