package com.agenticpilot.run;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** One thing that happened in a run. Texts are masked and shortened. */
@Entity
@Table(name = "agent_run_step")
public class AgentRunStep extends AuditedEntity {

    public static final int ARGUMENTS_MAX = 1000;
    public static final int CONTENT_MAX = 4000;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private StepType type;

    @Column(updatable = false, length = 40)
    private String tool;

    @Column(updatable = false, length = ARGUMENTS_MAX)
    private String arguments;

    @Column(updatable = false, length = CONTENT_MAX)
    private String content;

    @Column(name = "tokens_in", nullable = false, updatable = false)
    private int tokensIn;

    @Column(name = "tokens_out", nullable = false, updatable = false)
    private int tokensOut;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    protected AgentRunStep() {
    }

    public AgentRunStep(UUID runId, int ordinal, StepType type, String tool, String arguments, String content, int tokensIn, int tokensOut, long durationMs) {
        this.runId = runId;
        this.ordinal = ordinal;
        this.type = type;
        this.tool = tool;
        this.arguments = cut(arguments, ARGUMENTS_MAX);
        this.content = cut(content, CONTENT_MAX);
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.durationMs = durationMs;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    public UUID getRunId() { return runId; }
    public int getOrdinal() { return ordinal; }
    public StepType getType() { return type; }
    public String getTool() { return tool; }
    public String getArguments() { return arguments; }
    public String getContent() { return content; }
    public int getTokensIn() { return tokensIn; }
    public int getTokensOut() { return tokensOut; }
    public long getDurationMs() { return durationMs; }
}
