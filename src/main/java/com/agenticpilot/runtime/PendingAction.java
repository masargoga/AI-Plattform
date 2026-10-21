package com.agenticpilot.runtime;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A request that stopped at a Needs-approval guardrail and waits for an Admin. */
@Entity
@Table(name = "pending_action")
public class PendingAction extends AuditedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "rule_id", updatable = false)
    private UUID ruleId;

    @Column(nullable = false, updatable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PendingActionStatus status = PendingActionStatus.PENDING;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "requested_by", updatable = false)
    private AppUser requestedBy;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "audit_entry_id")
    private UUID auditEntryId;

    protected PendingAction() {
    }

    public PendingAction(UUID agentId, UUID conversationId, UUID ruleId, String description, AppUser requestedBy, UUID auditEntryId) {
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.ruleId = ruleId;
        this.description = description.length() > 500 ? description.substring(0, 500) : description;
        this.requestedBy = requestedBy;
        this.auditEntryId = auditEntryId;
    }

    /** Records the one decision. Only a pending action can be decided. */
    public void decide(boolean approve, AppUser by, Instant when) {
        if (status != PendingActionStatus.PENDING) {
            throw new IllegalStateException("Already decided");
        }
        this.status = approve ? PendingActionStatus.APPROVED : PendingActionStatus.DENIED;
        this.decidedBy = by;
        this.decidedAt = when;
    }

    public UUID getAgentId() { return agentId; }
    public UUID getConversationId() { return conversationId; }
    public UUID getRuleId() { return ruleId; }
    public String getDescription() { return description; }
    public PendingActionStatus getStatus() { return status; }
    public AppUser getRequestedBy() { return requestedBy; }
    public AppUser getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getAuditEntryId() { return auditEntryId; }
}
