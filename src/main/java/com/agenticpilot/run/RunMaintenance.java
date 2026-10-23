package com.agenticpilot.run;

import com.agenticpilot.common.JobLock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for runs, each under a database lock so one replica does it: a run that has not moved for a few minutes lost its
 * replica and is marked interrupted, and runs past their retention period are deleted with their steps.
 */
@Component
public class RunMaintenance {

    private static final Logger log = LoggerFactory.getLogger(RunMaintenance.class);

    private final AgentRunRepository runs;
    private final JdbcTemplate jdbc;
    private final JobLock lock;
    private final RunProperties props;

    public RunMaintenance(AgentRunRepository runs, JdbcTemplate jdbc, JobLock lock, RunProperties props) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.lock = lock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${agenticpilot.run.watchdog-interval:PT1M}", initialDelayString = "PT2M")
    void watchdog() {
        lock.tryRun("run-watchdog", Duration.ofSeconds(50), () -> markInterrupted(Instant.now()));
    }

    @Scheduled(cron = "${agenticpilot.run.retention-cron:0 25 3 * * *}")
    void retention() {
        lock.tryRun("run-retention", Duration.ofMinutes(30), () -> deleteExpired(Instant.now()));
    }

    /** Runs that have not moved for the stale time are finished as interrupted. Returns how many. */
    int markInterrupted(Instant now) {
        Instant cutoff = now.minus(Duration.ofMinutes(props.staleMinutes()));
        List<AgentRun> stale = runs.findByStatusInAndLastStepAtBefore(List.of(RunStatus.RUNNING, RunStatus.WAITING_FOR_APPROVAL), cutoff);
        int count = 0;
        for (AgentRun run : stale) {
            count += jdbc.update("""
                    update agent_run set status = 'FINISHED', stop_reason = 'INTERRUPTED', detail = 'The run stopped moving, probably because the server was restarted.',
                    ended_at = ?, pending_action_id = null, version = version + 1 where id = ? and status <> 'FINISHED'
                    """, Timestamp.from(now), run.getId());
        }
        if (count > 0) {
            log.info("Marked {} stalled runs as interrupted", count);
        }
        return count;
    }

    int deleteExpired(Instant now) {
        int deleted = runs.deleteExpired(now);
        if (deleted > 0) {
            log.info("Retention deleted {} expired runs", deleted);
        }
        return deleted;
    }
}
