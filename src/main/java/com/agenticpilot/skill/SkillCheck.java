package com.agenticpilot.skill;

import com.agenticpilot.guardrail.CatchPattern;

/** One check of a test case. MATCHES takes words separated by commas or a pattern between slashes, as custom rules do. */
public record SkillCheck(CheckType type, String value) {

    public static final int MAX_VALUE = 200;

    public boolean passes(String answer) {
        String text = answer == null ? "" : answer;
        return switch (type) {
            case CONTAINS -> text.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT));
            case NOT_CONTAINS -> !text.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT));
            case MATCHES -> CatchPattern.parse(value).map(p -> p.matches(text)).orElse(false);
        };
    }

    public boolean isValid() {
        return type != null && value != null && !value.isBlank() && value.length() <= MAX_VALUE
                && (type != CheckType.MATCHES || CatchPattern.parse(value).isPresent());
    }
}
