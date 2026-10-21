package com.agenticpilot.runtime;

import com.agenticpilot.audit.AuditEntry;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.user.AppUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Writes one audit entry per guardrail decision and creates the pending action for a Needs-approval stop. */
@Component
public class GuardrailRecorder {

    private final AuditService audit;
    private final PendingActionRepository pendingActions;

    public GuardrailRecorder(AuditService audit, PendingActionRepository pendingActions) {
        this.audit = audit;
        this.pendingActions = pendingActions;
    }

    /** Returns the id of the pending action when one was created. Join the caller's transaction. */
    public UUID record(String actor, UUID agentId, String agentName, UUID requestId, List<Decision> decisions, String pendingDescription,
                       UUID conversationId, AppUser requestedBy) {
        UUID pendingId = null;
        for (Decision d : decisions) {
            String event = d.ruleName() == null ? d.detail() : d.detail().replaceFirst("^Rule", "Rule “" + d.ruleName() + "”");
            AuditEntry entry = audit.recordAs(actor, agentId, agentName, event, d.outcome(), d.ruleId(),
                    Map.of("requestId", requestId.toString(), "category", d.kind().name()));
            if (d.outcome() == AuditOutcome.NEEDS_APPROVAL && pendingId == null && pendingDescription != null) {
                PendingAction action = pendingActions.save(new PendingAction(agentId, conversationId, d.ruleId(), pendingDescription, requestedBy, entry.getId()));
                pendingId = action.getId();
            }
        }
        return pendingId;
    }
}
