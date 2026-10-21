package com.agenticpilot.api;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An API key of one agent. Only the hash of the secret is stored; the key itself is shown once when it is created. */
@Entity
@Table(name = "api_key")
public class ApiKey extends AuditedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(nullable = false, updatable = false, length = 80)
    private String name;

    @Column(nullable = false, updatable = false, unique = true, length = 16)
    private String prefix;

    @Column(name = "secret_hash", nullable = false, updatable = false, length = 64)
    private String secretHash;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    public ApiKey(UUID agentId, String name, String prefix, String secretHash, AppUser createdBy) {
        this.agentId = agentId;
        this.name = name;
        this.prefix = prefix;
        this.secretHash = secretHash;
        this.createdBy = createdBy;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(Instant when) {
        if (revokedAt == null) {
            revokedAt = when;
        }
    }

    public void markUsed(Instant when) {
        lastUsedAt = when;
    }

    public UUID getAgentId() { return agentId; }
    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public String getSecretHash() { return secretHash; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
