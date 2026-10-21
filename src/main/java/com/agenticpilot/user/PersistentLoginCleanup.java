package com.agenticpilot.user;

import com.agenticpilot.common.JobLock;
import com.agenticpilot.config.AuthProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes remember-me tokens that nobody can use any more. Runs on one replica at a time. */
@Component
class PersistentLoginCleanup {

    private final JdbcTemplate jdbc;
    private final JobLock lock;
    private final AuthProperties props;

    PersistentLoginCleanup(JdbcTemplate jdbc, JobLock lock, AuthProperties props) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.props = props;
    }

    @Scheduled(cron = "${agenticpilot.auth.cleanup-cron:0 17 3 * * *}")
    void run() {
        lock.tryRun("persistent-login-cleanup", Duration.ofMinutes(10), () -> deleteExpired(Instant.now()));
    }

    int deleteExpired(Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(props.rememberMeHours()));
        return jdbc.update("delete from persistent_logins where last_used < ?", Timestamp.from(cutoff));
    }
}
