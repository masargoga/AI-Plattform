package com.agenticpilot.runtime;

import com.agenticpilot.common.JobLock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The daily retention job. With several replicas the database lock lets exactly one of them run it. */
@Component
@ConditionalOnProperty(name = "agenticpilot.retention.enabled", havingValue = "true", matchIfMissing = true)
class RetentionJob {

    private final RetentionService retention;
    private final JobLock lock;

    RetentionJob(RetentionService retention, JobLock lock) {
        this.retention = retention;
        this.lock = lock;
    }

    @Scheduled(cron = "${agenticpilot.retention.cron:0 15 3 * * *}")
    void run() {
        lock.tryRun("retention", Duration.ofMinutes(30), () -> retention.deleteExpired(Instant.now()));
    }
}
