package com.agenticpilot.user;

import com.agenticpilot.config.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after a number of failed sign-ins for a user name the account is locked for a while, also for
 * the right password. The count is kept in memory per replica, so with N replicas an attacker gets at most N times the limit.
 * It does not matter whether the user name exists, so the lock reveals nothing.
 */
@Component
public class LoginAttempts {

    private record Window(int failures, Instant lockedUntil, Instant last) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AuthProperties props;

    public LoginAttempts(AuthProperties props) {
        this.props = props;
    }

    public boolean isLocked(String username) {
        Window w = windows.get(key(username));
        return w != null && w.lockedUntil() != null && w.lockedUntil().isAfter(Instant.now());
    }

    public void failed(String username) {
        Instant now = Instant.now();
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> Duration.between(e.getValue().last(), now).toHours() >= 1);
        }
        windows.merge(key(username), new Window(1, null, now), (old, fresh) -> {
            boolean expired = Duration.between(old.last(), now).toMinutes() >= props.lockMinutes();
            int failures = expired ? 1 : old.failures() + 1;
            Instant until = failures >= props.maxFailedLogins() ? now.plus(Duration.ofMinutes(props.lockMinutes())) : null;
            return new Window(failures, until, now);
        });
    }

    public void succeeded(String username) {
        windows.remove(key(username));
    }

    @EventListener
    void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        failed(String.valueOf(event.getAuthentication().getName()));
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        succeeded(event.getAuthentication().getName());
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }
}
