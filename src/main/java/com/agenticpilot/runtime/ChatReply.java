package com.agenticpilot.runtime;

import java.util.List;
import java.util.UUID;

/** The answer to one chat message, with what happened on the way: guardrail decisions, skill and sources used. */
public record ChatReply(UUID conversationId, Status status, String text, List<TraceItem> trace, UUID pendingActionId) {

    public enum Status { ANSWERED, BLOCKED, WAITING_FOR_APPROVAL, REFUSED, UNAVAILABLE }

    public record TraceItem(Type type, String text) {

        public enum Type { GUARDRAIL, SKILL, SOURCE, WAITING, NOTICE }
    }
}
