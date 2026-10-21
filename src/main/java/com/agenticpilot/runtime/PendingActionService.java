package com.agenticpilot.runtime;

import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.user.AppUser;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pending actions created by Needs-approval guardrails. Any Admin can approve or deny; each decision is audited. */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class PendingActionService {

    private final PendingActionRepository actions;
    private final AgentRepository agents;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public PendingActionService(PendingActionRepository actions, AgentRepository agents, AuditService audit, CurrentUser currentUser) {
        this.actions = actions;
        this.agents = agents;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PendingAction get(UUID id) {
        return actions.findById(id).orElseThrow(() -> new NotFoundException("Pending action"));
    }

    /** The pending actions that belong to the given audit entries, keyed by audit entry id. */
    @Transactional(readOnly = true)
    public Map<UUID, PendingAction> forAuditEntries(Collection<UUID> auditEntryIds) {
        Map<UUID, PendingAction> result = new HashMap<>();
        if (!auditEntryIds.isEmpty()) {
            actions.findByAuditEntryIdIn(auditEntryIds).forEach(a -> result.put(a.getAuditEntryId(), a));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public int openCount() {
        return actions.countByStatus(PendingActionStatus.PENDING);
    }

    public PendingAction approve(UUID id) {
        return decide(id, true);
    }

    public PendingAction deny(UUID id) {
        return decide(id, false);
    }

    private PendingAction decide(UUID id, boolean approve) {
        AppUser me = currentUser.requireAdmin();
        PendingAction action = get(id);
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw new InvalidInputException("error.pending.decided");
        }
        action.decide(approve, me, Instant.now());
        String agentName = agents.findById(action.getAgentId()).map(a -> a.getName()).orElse(null);
        audit.record(action.getAgentId(), agentName, "Pending action " + (approve ? "approved" : "denied") + " by " + me.getDisplayName(),
                approve ? AuditOutcome.ALLOWED : AuditOutcome.BLOCKED, action.getRuleId(), Map.of("pendingAction", action.getId().toString()));
        return action;
    }
}
