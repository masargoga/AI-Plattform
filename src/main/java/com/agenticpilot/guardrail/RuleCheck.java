package com.agenticpilot.guardrail;

/** The "Check" button in the rule editor: shows what a rule would do with a sample message. */
public final class RuleCheck {

    public enum Kind { NO_CATCHES, INVALID_PATTERN, NO_SAMPLE, NO_MATCH, MATCH }

    /** For MATCH: the action and, depending on it, the text the agent would say or see. */
    public record Result(Kind kind, RuleAction action, String text) {
    }

    private RuleCheck() {
    }

    public static Result evaluate(String catches, RuleAction action, String message, String sample) {
        if (catches == null || catches.isBlank()) {
            return new Result(Kind.NO_CATCHES, null, "");
        }
        var pattern = CatchPattern.parse(catches);
        if (pattern.isEmpty()) {
            return new Result(Kind.INVALID_PATTERN, null, "");
        }
        if (sample == null || sample.isBlank()) {
            return new Result(Kind.NO_SAMPLE, null, "");
        }
        if (!pattern.get().matches(sample)) {
            return new Result(Kind.NO_MATCH, null, "");
        }
        String msg = message == null ? "" : message;
        String text = switch (action) {
            case BLOCK -> msg.isEmpty() ? "I cannot help with that." : msg;
            case MASK -> pattern.get().replaceAll(sample, "[hidden]");
            case APPROVE -> msg;
            case NOTE -> msg;
        };
        return new Result(Kind.MATCH, action, text);
    }
}
