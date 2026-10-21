package com.agenticpilot.common;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A lock in the database for background jobs. With several replicas every replica fires the scheduled job, but only the
 * one that gets the lock runs it. The lock expires by itself, so a replica that dies while holding it blocks nothing
 * for longer than the hold time.
 */
@Component
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    private final JdbcTemplate jdbc;
    private final String owner;

    public JobLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        this.owner = host + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Runs the job if this replica gets the lock. Returns false when another replica holds it. */
    public boolean tryRun(String name, Duration hold, Runnable job) {
        if (!acquire(name, hold)) {
            log.debug("Job {} is running on another replica", name);
            return false;
        }
        try {
            job.run();
        } finally {
            release(name);
        }
        return true;
    }

    boolean acquire(String name, Duration hold) {
        Instant now = Instant.now();
        Timestamp until = Timestamp.from(now.plus(hold));
        int taken = jdbc.update("update job_lock set locked_until = ?, locked_by = ? where name = ? and locked_until < ?",
                until, owner, name, Timestamp.from(now));
        if (taken == 1) {
            return true;
        }
        try {
            return jdbc.update("insert into job_lock (name, locked_until, locked_by) values (?, ?, ?)", name, until, owner) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    void release(String name) {
        jdbc.update("update job_lock set locked_until = ? where name = ? and locked_by = ?", Timestamp.from(Instant.now().minusSeconds(1)), name, owner);
    }
}
