package com.agenticpilot.api;

import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.runtime.PendingAction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of API v1. */
public final class ApiDtos {

    private ApiDtos() {
    }

    @Schema(description = "An agent the API key may use")
    public record AgentSummary(UUID id, String name, String job, @Schema(example = "managed") String kind, @Schema(example = "active") String status) {
    }

    @Schema(description = "The agents the key may use. A key belongs to one agent, so the list has one entry.")
    public record AgentList(List<AgentSummary> agents) {
    }

    @Schema(description = "A message for a managed agent")
    public record MessageRequest(
            @Schema(description = "What the user says. At most 4000 characters.", requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(description = "Continue an earlier conversation of this key", nullable = true) UUID conversationId,
            @Schema(description = "true to receive the answer as server-sent events", nullable = true) Boolean stream) {
    }

    @Schema(description = "A guardrail decision. It names the category and the rule, never the value that triggered it.")
    public record DecisionDto(
            @Schema(example = "PERSONAL_DATA") String category,
            @Schema(example = "masked") String outcome,
            @Schema(description = "Name of the custom rule, if one decided", nullable = true) String rule) {

        static DecisionDto of(Decision d) {
            return new DecisionDto(d.kind().name(), d.outcome().name().toLowerCase(), d.ruleName());
        }
    }

    @Schema(description = "Where to follow a pending action")
    public record ApprovalRef(UUID id, @Schema(example = "pending") String status, @Schema(example = "/api/v1/approvals/3f2b...") String url) {
    }

    @Schema(description = "The answer of a managed agent")
    public record MessageResponse(
            UUID requestId,
            @Schema(description = "Null when the agent does not store conversations", nullable = true) UUID conversationId,
            @Schema(description = "answered, blocked or needs_approval", example = "answered") String status,
            @Schema(description = "The answer, or what the agent says when a guardrail stopped the request") String answer,
            List<String> skillsUsed,
            List<String> sourcesUsed,
            List<DecisionDto> decisions,
            @Schema(nullable = true) ApprovalRef approval) {
    }

    @Schema(description = "Text to check against an agent's guardrails")
    public record CheckRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID agentId,
            @Schema(description = "The text. At most 20000 characters.", requiredMode = Schema.RequiredMode.REQUIRED) String text,
            @Schema(description = "input (default): text going to the agent. output: text the agent is about to say.", allowableValues = {"input", "output"}, nullable = true) String direction) {
    }

    @Schema(description = "A custom rule that matched")
    public record MatchedRule(UUID id, String name, @Schema(example = "block") String action) {
    }

    @Schema(description = "What to do with the text")
    public record CheckResponse(
            UUID requestId,
            @Schema(description = "allow, mask, block or needs_approval", example = "mask") String decision,
            @Schema(description = "The text to use. For mask it is the transformed text; empty for block and needs_approval.") String text,
            @Schema(description = "For block and needs_approval: what to tell the user instead") String message,
            List<MatchedRule> matchedRules,
            List<DecisionDto> decisions,
            @Schema(description = "Notes from custom rules to add to the agent's answer") List<String> notes,
            @Schema(nullable = true) ApprovalRef approval) {
    }

    @Schema(description = "A pending action")
    public record ApprovalResponse(
            UUID id,
            @Schema(example = "pending") String status,
            UUID agentId,
            String description,
            Instant createdAt,
            @Schema(nullable = true) Instant decidedAt) {

        static ApprovalResponse of(PendingAction a) {
            return new ApprovalResponse(a.getId(), a.getStatus().name().toLowerCase(), a.getAgentId(), a.getDescription(), a.getCreatedAt(), a.getDecidedAt());
        }
    }

    static ApprovalRef approvalRef(UUID id) {
        return id == null ? null : new ApprovalRef(id, "pending", "/api/v1/approvals/" + id);
    }

    @Schema(description = "Start a run of a managed agent")
    public record RunRequest(@Schema(description = "The input the agent works on, for example a customer id. At most 1000 characters.", requiredMode = Schema.RequiredMode.REQUIRED) String input) {
    }

    @Schema(description = "One thing that happened in a run. Texts are masked and shortened.")
    public record RunStepDto(int n, @Schema(example = "tool_result") String type, @Schema(nullable = true) String tool,
                             @Schema(nullable = true) String arguments, @Schema(nullable = true) String content, int tokensIn, int tokensOut, long durationMs) {
    }

    @Schema(description = "A run of an agent. Poll it until the status is finished.")
    public record RunResponse(
            UUID id,
            UUID agentId,
            @Schema(description = "running, waiting_for_approval or finished", example = "finished") String status,
            @Schema(description = "Why it ended: done, cannot_complete, step_limit, time_limit, token_limit, stopped_by_user, refused_too_often, blocked, denied, failed or interrupted",
                    nullable = true, example = "done") String stopReason,
            @Schema(description = "The result of the run, or what the agent says when it could not finish", nullable = true) String result,
            @Schema(nullable = true) String detail,
            @Schema(description = "The input after the guardrails (masked)", nullable = true) String input,
            int steps, int tokens, Instant startedAt, @Schema(nullable = true) Instant endedAt,
            @Schema(description = "Present when the run waits for a person", nullable = true) ApprovalRef approval,
            List<RunStepDto> timeline) {
    }

    @Schema(description = "A run that was started")
    public record RunStarted(UUID id, @Schema(example = "running") String status, @Schema(example = "/api/v1/runs/3f2b...") String url) {
    }
}
