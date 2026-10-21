package com.agenticpilot.runtime;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes conversations whose retention period has passed, and records how many. */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final ConversationRepository conversations;
    private final AuditService audit;

    public RetentionService(ConversationRepository conversations, AuditService audit) {
        this.conversations = conversations;
        this.audit = audit;
    }

    /** Runs without a signed-in user (the job); the audit entry then names "system". */
    @Transactional
    public int deleteExpired(Instant now) {
        int deleted = conversations.deleteExpired(now);
        if (deleted > 0) {
            log.info("Retention job deleted {} expired conversations", deleted);
            audit.workspace("Retention job deleted " + deleted + (deleted == 1 ? " conversation" : " conversations"), AuditOutcome.ALLOWED, null,
                    Map.of("deleted", deleted));
        }
        return deleted;
    }
}
