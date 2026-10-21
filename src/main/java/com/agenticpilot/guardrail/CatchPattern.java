package com.agenticpilot.guardrail;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns the "Catches" field of a custom rule into a pattern: either words separated by commas (case-insensitive, whole
 * words) or a pattern between slashes such as {@code /KD-\d{4,}/}. Matching is time-bounded so a pattern written by a
 * user cannot hang a request (catastrophic backtracking).
 */
public final class CatchPattern {

    public static final int MAX_LENGTH = 500;
    private static final long MATCH_BUDGET_NANOS = 100_000_000L;
    private static final Pattern SLASHED = Pattern.compile("^/(.+)/([a-z]*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final Pattern pattern;

    private CatchPattern(Pattern pattern) {
        this.pattern = pattern;
    }

    /** Empty when the field is blank or the pattern is not valid. */
    public static Optional<CatchPattern> parse(String catches) {
        String m = catches == null ? "" : catches.strip();
        if (m.isEmpty() || m.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        try {
            var slashed = SLASHED.matcher(m);
            if (slashed.matches()) {
                return Optional.of(new CatchPattern(Pattern.compile(slashed.group(1), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
            }
            String words = Arrays.stream(m.split(","))
                    .map(String::strip)
                    .filter(w -> !w.isEmpty())
                    .map(Pattern::quote)
                    .reduce((a, b) -> a + "|" + b)
                    .orElse("");
            if (words.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CatchPattern(Pattern.compile("\\b(?:" + words + ")\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
    }

    public boolean matches(String text) {
        try {
            return pattern.matcher(bounded(text)).find();
        } catch (MatchTimeoutException e) {
            return false;
        }
    }

    /** Replaces every match with the given placeholder. Returns the text unchanged if matching takes too long. */
    public String replaceAll(String text, String replacement) {
        try {
            return pattern.matcher(bounded(text)).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
        } catch (MatchTimeoutException e) {
            return text;
        }
    }

    private static CharSequence bounded(String text) {
        return new DeadlineCharSequence(text == null ? "" : text, System.nanoTime() + MATCH_BUDGET_NANOS);
    }

    private static final class MatchTimeoutException extends RuntimeException {
        MatchTimeoutException() {
            super("pattern took too long", null, false, false);
        }
    }

    private record DeadlineCharSequence(CharSequence inner, long deadline) implements CharSequence {

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new MatchTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
