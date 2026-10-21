package com.agenticpilot.runtime;

import com.agenticpilot.guardrail.Decision;
import java.util.List;
import java.util.UUID;

/**
 * The answer to one chat message, with what happened on the way: guardrail decisions, skill and sources used.
 * The trace is for display; decisions carry the categories and rules for the API.
 */
public record ChatReply(UUID conversationId, Status status, String text, List<TraceItem> trace, UUID pendingActionId,
                        List<Decision> decisions, UUID requestId) {

    public enum Status { ANSWERED, BLOCKED, WAITING_FOR_APPROVAL, REFUSED, UNAVAILABLE }

    public record TraceItem(Type type, String text) {

        public enum Type { GUARDRAIL, SKILL, SOURCE, WAITING, NOTICE }
    }

    /** The names of the skills and data sources shown in the trace. */
    public List<String> traceTexts(TraceItem.Type type) {
        return trace.stream().filter(t -> t.type() == type).map(TraceItem::text).toList();
    }
}
