package com.agenticpilot.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One line of the audit log. Append-only: the entity is immutable and {@link AuditEntryRepository} offers no way to
 * update or delete. The agent name is stored too, so entries stay readable when an agent is gone.
 */
@Entity
@Immutable
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    @Column(name = "agent_name", length = 80, updatable = false)
    private String agentName;

    @Column(nullable = false, length = 64, updatable = false)
    private String actor;

    @Column(nullable = false, length = 500, updatable = false)
    private String event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AuditOutcome outcome;

    @Column(name = "rule_id", updatable = false)
    private UUID ruleId;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(length = 4000, updatable = false)
    private String details;

    protected AuditEntry() {
    }

    public AuditEntry(Instant occurredAt, UUID agentId, String agentName, String actor, String event, AuditOutcome outcome,
                      UUID ruleId, String requestId, String details) {
        this.occurredAt = occurredAt;
        this.agentId = agentId;
        this.agentName = agentName;
        this.actor = actor;
        this.event = event.length() > 500 ? event.substring(0, 500) : event;
        this.outcome = outcome;
        this.ruleId = ruleId;
        this.requestId = requestId;
        this.details = details;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getActor() { return actor; }
    public String getEvent() { return event; }
    public AuditOutcome getOutcome() { return outcome; }
    public UUID getRuleId() { return ruleId; }
    public String getRequestId() { return requestId; }
    public String getDetails() { return details; }
}
