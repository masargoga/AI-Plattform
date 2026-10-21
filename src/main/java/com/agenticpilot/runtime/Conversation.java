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

/** A stored conversation. It is deleted by the retention job once expiresAt has passed; null means it never expires. */
@Entity
@Table(name = "conversation")
public class Conversation extends AuditedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private ConversationChannel channel;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "started_by", updatable = false)
    private AppUser startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected Conversation() {
    }

    public Conversation(UUID agentId, ConversationChannel channel, AppUser startedBy, Instant startedAt, Instant expiresAt) {
        this.agentId = agentId;
        this.channel = channel;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getAgentId() { return agentId; }
    public ConversationChannel getChannel() { return channel; }
    public AppUser getStartedBy() { return startedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
