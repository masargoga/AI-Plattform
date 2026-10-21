package com.agenticpilot.runtime;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The daily retention job. Only one replica should run it; with several replicas it is guarded by a database lock (M5). */
@Component
@ConditionalOnProperty(name = "agenticpilot.retention.enabled", havingValue = "true", matchIfMissing = true)
class RetentionJob {

    private final RetentionService retention;

    RetentionJob(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${agenticpilot.retention.cron:0 15 3 * * *}")
    void run() {
        retention.deleteExpired(Instant.now());
    }
}
