package com.agenticpilot.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A sync cannot survive a restart. Sources left in the Syncing state by a stopped instance are marked as failed. */
@Component
@Order(5)
class SyncRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncRecovery.class);

    private final DataSourceRepository sources;

    SyncRecovery(DataSourceRepository sources) {
        this.sources = sources;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var stuck = sources.findAll().stream().filter(s -> s.getStatus() == SyncStatus.SYNCING).toList();
        stuck.forEach(s -> s.markError("The sync was interrupted. Start it again."));
        if (!stuck.isEmpty()) {
            log.info("Marked {} interrupted syncs as failed", stuck.size());
        }
    }
}
