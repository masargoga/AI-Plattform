package com.agenticpilot.run;

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

/**
 * One execution of an agent. The row is created when the run starts; while it runs the executor updates progress with plain
 * SQL (so a stop request from another request or replica cannot collide with it). Input and result are stored masked.
 */
@Entity
@Table(name = "agent_run")
public class AgentRun extends AuditedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "agent_version", nullable = false, updatable = false)
    private int agentVersion;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "started_by", updatable = false)
    private AppUser startedBy;

    @Column(name = "api_key_id", updatable = false)
    private UUID apiKeyId;

    @Column(nullable = false, updatable = false, length = 64)
    private String actor;

    @Column(nullable = false, updatable = false)
    private boolean draft;

    @Column(length = 1000)
    private String input;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RunStatus status = RunStatus.RUNNING;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 24)
    private StopReason stopReason;

    @Column(length = 10000)
    private String result;

    @Column(length = 500)
    private String detail;

    @Column(name = "max_steps", nullable = false, updatable = false)
    private int maxSteps;

    @Column(name = "max_seconds", nullable = false, updatable = false)
    private int maxSeconds;

    @Column(name = "max_tokens", nullable = false, updatable = false)
    private int maxTokens;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "tokens_in", nullable = false)
    private int tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private int tokensOut;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "pending_action_id")
    private UUID pendingActionId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "last_step_at", nullable = false)
    private Instant lastStepAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected AgentRun() {
    }

    public AgentRun(UUID agentId, int agentVersion, AppUser startedBy, UUID apiKeyId, String actor, boolean draft, int maxSteps, int maxSeconds,
                    int maxTokens, Instant startedAt, Instant expiresAt) {
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.startedBy = startedBy;
        this.apiKeyId = apiKeyId;
        this.actor = actor;
        this.draft = draft;
        this.maxSteps = maxSteps;
        this.maxSeconds = maxSeconds;
        this.maxTokens = maxTokens;
        this.startedAt = startedAt;
        this.lastStepAt = startedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isFinished() {
        return status == RunStatus.FINISHED;
    }

    public UUID getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
    public AppUser getStartedBy() { return startedBy; }
    public UUID getApiKeyId() { return apiKeyId; }
    public String getActor() { return actor; }
    public boolean isDraft() { return draft; }
    public String getInput() { return input; }
    public RunStatus getStatus() { return status; }
    public StopReason getStopReason() { return stopReason; }
    public String getResult() { return result; }
    public String getDetail() { return detail; }
    public int getMaxSteps() { return maxSteps; }
    public int getMaxSeconds() { return maxSeconds; }
    public int getMaxTokens() { return maxTokens; }
    public int getStepCount() { return stepCount; }
    public int getTokensIn() { return tokensIn; }
    public int getTokensOut() { return tokensOut; }
    public boolean isCancelRequested() { return cancelRequested; }
    public UUID getPendingActionId() { return pendingActionId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Instant getLastStepAt() { return lastStepAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
