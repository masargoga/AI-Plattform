package com.agenticpilot.guardrail;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.guardrail.Decision.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the output guardrails to an answer that arrives in pieces, so text can be released before the model has
 * finished. It works line by line: a line is checked (personal data masked, record limit counted) before it is released,
 * and nothing of a line leaves the guard until the line is complete. The "Skill used:" line the prompt asks for is taken
 * out. At the end it appends the notes from custom rules. The result is the same text {@link GuardrailEngine#checkOutput}
 * produces for the complete answer.
 */
public final class StreamingOutputGuard {

    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*\\u2022]|\\d+[.)])\\s+.*");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|?\\s*$");
    private static final Pattern SKILL_LINE = Pattern.compile("(?i)^\\s*skill used:\\s*(.+?)\\s*$");

    /** The complete released text, the output decisions and the skill the model reported (null if none). */
    public record Result(String tail, String fullText, List<Decision> decisions, String skillName) {
    }

    private final AgentGuardContext ctx;
    private final List<String> notes;
    private final List<String> knownSkills;

    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder released = new StringBuilder();
    private String held;          // a completed table row waiting for the next line (header detection)
    private int blanks;           // blank lines waiting for the next non-blank line (the end of the answer is trimmed)
    private boolean any;
    private int records;
    private boolean cut;
    private boolean masked;
    private String skill;

    public StreamingOutputGuard(AgentGuardContext ctx, List<String> notes, List<String> knownSkills) {
        this.ctx = ctx;
        this.notes = notes == null ? List.of() : notes;
        this.knownSkills = knownSkills == null ? List.of() : knownSkills;
    }

    /** Takes the next piece of model text and returns what may be released now (possibly empty). */
    public String accept(String piece) {
        buffer.append(piece);
        StringBuilder out = new StringBuilder();
        int nl;
        while ((nl = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, nl);
            buffer.delete(0, nl + 1);
            line(line, out);
        }
        return emit(out);
    }

    /** Ends the answer: releases the last line, then the "showing the first N" remark and the notes. */
    public Result finish() {
        StringBuilder out = new StringBuilder();
        if (buffer.length() > 0) {
            String line = buffer.toString();
            buffer.setLength(0);
            line(line, out);
        }
        flushHeld(null, out);
        List<Decision> decisions = new ArrayList<>();
        if (masked) {
            decisions.add(Decision.of(Kind.PERSONAL_DATA, AuditOutcome.MASKED, "Personal data replaced in the answer"));
        }
        if (cut) {
            out.append("\n\n(Showing the first ").append(ctx.maxRecords()).append(" records.)");
            decisions.add(Decision.of(Kind.RECORD_LIMIT, AuditOutcome.ALLOWED, "Answer shortened to " + ctx.maxRecords() + " records"));
        }
        for (String note : notes) {
            out.append("\n\nNote: ").append(note);
        }
        String tail = emit(out);
        return new Result(tail, released.toString(), decisions, skill);
    }

    private void line(String line, StringBuilder out) {
        Matcher sk = SKILL_LINE.matcher(line);
        if (sk.matches()) {
            String value = sk.group(1).replaceAll("^[\"'\u201c\u2018\\s]+|[\"'\u201d\u2019.\\s]+$", "");
            skill = knownSkills.stream().filter(n -> n.equalsIgnoreCase(value)).findFirst().orElse(null);
            return;
        }
        if (held != null) {
            flushHeld(line, out);
        }
        String text = line;
        if (ctx.personalData() != PersonalDataMode.ALLOW && GuardrailEngine.containsPersonalData(text)) {
            text = GuardrailEngine.maskPersonalData(text);
            masked = true;
        }
        if (TABLE_ROW.matcher(text).matches() && !TABLE_SEPARATOR.matcher(text).matches()) {
            held = text; // may be a table header: decide when the next line is known
            return;
        }
        releaseLine(text, false, out);
    }

    private void flushHeld(String next, StringBuilder out) {
        if (held == null) {
            return;
        }
        boolean header = next != null && TABLE_ROW.matcher(next).matches() && TABLE_SEPARATOR.matcher(next).matches();
        String h = held;
        held = null;
        releaseLine(h, header, out);
    }

    private void releaseLine(String line, boolean header, StringBuilder out) {
        boolean separator = TABLE_ROW.matcher(line).matches() && TABLE_SEPARATOR.matcher(line).matches();
        boolean record = LIST_LINE.matcher(line).matches() || (TABLE_ROW.matcher(line).matches() && !separator && !header);
        if (record && ++records > ctx.maxRecords()) {
            cut = true;
            return;
        }
        if (line.isBlank()) {
            blanks++;
            return;
        }
        if (any) {
            out.append("\n".repeat(blanks + 1));
        }
        out.append(line);
        any = true;
        blanks = 0;
    }

    private String emit(StringBuilder out) {
        String s = out.toString();
        released.append(s);
        return s;
    }
}
