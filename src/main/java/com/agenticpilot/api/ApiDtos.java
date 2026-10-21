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
}
