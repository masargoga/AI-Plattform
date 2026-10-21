package com.agenticpilot.source;

import com.agenticpilot.common.JobLock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A sync cannot survive the replica that runs it. Sources that have been in the Syncing state for much longer than a sync
 * can take are marked as failed, at startup and every few minutes. Younger ones are left alone, because with several
 * replicas another replica may be running them right now.
 */
@Component
@Order(5)
class SyncRecovery implements ApplicationRunner {

    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(SyncRecovery.class);

    private final DataSourceRepository sources;
    private final JobLock lock;
    private final TransactionTemplate tx;

    SyncRecovery(DataSourceRepository sources, JobLock lock, PlatformTransactionManager txManager) {
        this.sources = sources;
        this.lock = lock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        failStaleSyncs();
    }

    @Scheduled(fixedDelayString = "${agenticpilot.sync.watchdog-interval:PT5M}", initialDelayString = "PT5M")
    void scheduled() {
        lock.tryRun("sync-watchdog", Duration.ofMinutes(2), this::failStaleSyncs);
    }

    void failStaleSyncs() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        tx.executeWithoutResult(status -> {
            var stuck = sources.findByStatusAndUpdatedAtBefore(SyncStatus.SYNCING, cutoff);
            stuck.forEach(s -> s.markError("The sync was interrupted. Start it again."));
            if (!stuck.isEmpty()) {
                log.info("Marked {} interrupted syncs as failed", stuck.size());
            }
        });
    }
}
