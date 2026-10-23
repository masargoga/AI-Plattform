package com.agenticpilot.guardrail;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.guardrail.Decision.Kind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guardrail pipeline. Plain Java without UI or database dependency; the test chat, the message API and the check API
 * use the same engine, so behaviour is identical everywhere.
 *
 * <p>Order of checks (spec): special categories, personal data, actions that need approval, then custom rules in order
 * of creation. The first Block or Needs approval stops the request; masks and notes accumulate. After the model answers,
 * {@link #checkOutput} masks personal data, applies the record limit and appends notes.
 *
 * <p>Detection is pattern based (emails, IBANs, phone numbers with country code, a list of health and other special
 * category terms). It does not recognise names or free-text addresses; the model-side instructions and the workspace
 * policy cover what patterns cannot.
 */
public final class GuardrailEngine {

    public static final String BLOCK_SPECIAL = "I cannot process health or other special-category data, and I did not store your message. Please contact a person about this.";
    public static final String REVIEW_SPECIAL = "This message touches special-category data. I paused and sent it to a reviewer. You will get an answer once it is approved.";
    public static final String BLOCK_PERSONAL = "Your message contains personal data, so I cannot process it. Please remove it and ask again.";
    public static final String WAIT_ACTION = "This needs approval from a person first. I prepared it and will continue once it is approved.";
    public static final String DEFAULT_BLOCK = "I cannot help with that.";
    public static final String DEFAULT_APPROVAL = "This needs approval from a person first.";

    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){3,7}(?:\\s?[A-Z0-9]{1,3})?\\b");
    static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+\\d[\\d ()/-]{7,}\\d");
    static final Pattern SPECIAL = Pattern.compile("\\b(sick|sickness|illness|ill|diagnos\\w*|medical|medication|health|pregnan\\w*|religio\\w*|disabilit\\w*|cancer|depress\\w*|therapy|therapist|ethnic\\w*|sexual orientation|trade union|union member\\w*|political opinion\\w*)\\b",
            Pattern.CASE_INSENSITIVE);
    static final Pattern INSTRUCTION_LIKE = Pattern.compile(
            "(ignore|disregard|forget)\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier)\\s+(instructions?|rules?|prompts?)"
                    + "|you\\s+(must|should)\\s+now\\s+"
                    + "|new\\s+instructions?\\s*:"
                    + "|(reveal|print|show)\\s+(your\\s+)?(system\\s+prompt|instructions)"
                    + "|\\bact\\s+as\\s+(the\\s+)?(admin|administrator|system)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<ApprovalAction, Pattern> ACTION_INTENT = new EnumMap<>(ApprovalAction.class);
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*\\u2022]|\\d+[.)])\\s+.*");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|?\\s*$");

    static {
        ACTION_INTENT.put(ApprovalAction.REFUND, Pattern.compile("\\b(refund\\w*|reimburs\\w*|money back)\\b", Pattern.CASE_INSENSITIVE));
        ACTION_INTENT.put(ApprovalAction.EMAIL, Pattern.compile("\\b(send|write|draft)\\b[^.!?\\n]{0,40}\\be-?mails?\\b", Pattern.CASE_INSENSITIVE));
        ACTION_INTENT.put(ApprovalAction.DELETE, Pattern.compile("\\b(delete|remove|erase|change|edit)\\b[^.!?\\n]{0,40}\\b(records?|accounts?|entries|entry|orders?|customers?)\\b", Pattern.CASE_INSENSITIVE));
        ACTION_INTENT.put(ApprovalAction.SHARE, Pattern.compile("\\bshare\\b[^.!?\\n]{0,40}\\b(files?|documents?|contracts?|folders?)\\b", Pattern.CASE_INSENSITIVE));
        ACTION_INTENT.put(ApprovalAction.TICKET, Pattern.compile("\\b(create|open|file|raise)\\b[^.!?\\n]{0,30}\\btickets?\\b", Pattern.CASE_INSENSITIVE));
    }

    private GuardrailEngine() {
    }

    // ----- before the model -----

    public static InputResult checkInput(String text, AgentGuardContext ctx) {
        return check(text, ctx, true);
    }

    /**
     * The same checks for text that comes from a data source during an agent run. The built-in "actions that need approval"
     * look at what a person asks for, not at data, so they are skipped; special categories, personal data and custom
     * rules apply.
     */
    public static InputResult checkData(String text, AgentGuardContext ctx) {
        return check(text, ctx, false);
    }

    private static InputResult check(String text, AgentGuardContext ctx, boolean approvalActions) {
        String input = text == null ? "" : text;
        List<Decision> decisions = new ArrayList<>();

        // 1. special categories
        if (ctx.specialData() != SpecialDataMode.ALLOW && SPECIAL.matcher(input).find()) {
            if (ctx.specialData() == SpecialDataMode.BLOCK) {
                decisions.add(Decision.of(Kind.SPECIAL_DATA, AuditOutcome.BLOCKED, "Message mentioned special-category data"));
                return new InputResult.Stop(AuditOutcome.BLOCKED, BLOCK_SPECIAL, null, decisions);
            }
            decisions.add(Decision.of(Kind.SPECIAL_DATA, AuditOutcome.NEEDS_APPROVAL, "Message mentioned special-category data"));
            return new InputResult.Stop(AuditOutcome.NEEDS_APPROVAL, REVIEW_SPECIAL, "Message mentions special-category data and needs a review", decisions);
        }

        // 2. personal data
        String current = input;
        if (ctx.personalData() != PersonalDataMode.ALLOW && containsPersonalData(input)) {
            if (ctx.personalData() == PersonalDataMode.BLOCK) {
                decisions.add(Decision.of(Kind.PERSONAL_DATA, AuditOutcome.BLOCKED, "Message with personal data rejected"));
                return new InputResult.Stop(AuditOutcome.BLOCKED, BLOCK_PERSONAL, null, decisions);
            }
            current = maskPersonalData(input);
            decisions.add(Decision.of(Kind.PERSONAL_DATA, AuditOutcome.MASKED, "Personal data replaced before processing"));
        }

        // 3. built-in actions that need approval
        for (ApprovalAction action : ApprovalAction.values()) {
            Pattern intent = ACTION_INTENT.get(action);
            if (approvalActions && ctx.approvals().contains(action) && intent.matcher(input).find()) {
                decisions.add(Decision.of(Kind.APPROVAL_ACTION, AuditOutcome.NEEDS_APPROVAL, "Action needs approval: " + action.name().toLowerCase()));
                return new InputResult.Stop(AuditOutcome.NEEDS_APPROVAL, WAIT_ACTION, "Agent prepared an action that needs approval: " + action.name().toLowerCase(), decisions);
            }
        }

        // 4. custom rules in order of creation
        List<String> notes = new ArrayList<>();
        for (RuleSpec rule : ctx.rules()) {
            if (!rule.pattern().matches(current)) {
                continue;
            }
            String message = rule.message() == null ? "" : rule.message();
            switch (rule.action()) {
                case BLOCK -> {
                    decisions.add(new Decision(Kind.CUSTOM_RULE, AuditOutcome.BLOCKED, rule.id(), rule.name(), "Rule blocked a message"));
                    return new InputResult.Stop(AuditOutcome.BLOCKED, message.isEmpty() ? DEFAULT_BLOCK : message, null, decisions);
                }
                case APPROVE -> {
                    decisions.add(new Decision(Kind.CUSTOM_RULE, AuditOutcome.NEEDS_APPROVAL, rule.id(), rule.name(), "Rule is waiting for approval"));
                    String shown = message.isEmpty() ? DEFAULT_APPROVAL : message;
                    return new InputResult.Stop(AuditOutcome.NEEDS_APPROVAL, shown + " I will continue once it is approved.", shown, decisions);
                }
                case MASK -> {
                    current = rule.pattern().replaceAll(current, "[hidden]");
                    decisions.add(new Decision(Kind.CUSTOM_RULE, AuditOutcome.MASKED, rule.id(), rule.name(), "Rule hid text before processing"));
                }
                case NOTE -> {
                    if (!message.isEmpty()) {
                        notes.add(message);
                    }
                    decisions.add(new Decision(Kind.CUSTOM_RULE, AuditOutcome.ALLOWED, rule.id(), rule.name(), "Rule added a note"));
                }
            }
        }
        return new InputResult.Proceed(current, notes, decisions);
    }

    // ----- after the model -----

    public record OutputResult(String text, List<Decision> decisions) {
    }

    /** Masks personal data in the answer, applies the record limit and appends the notes from custom rules. */
    public static OutputResult checkOutput(String answer, AgentGuardContext ctx, List<String> notes) {
        String text = answer == null ? "" : answer;
        List<Decision> decisions = new ArrayList<>();
        if (ctx.personalData() != PersonalDataMode.ALLOW && containsPersonalData(text)) {
            text = maskPersonalData(text);
            decisions.add(Decision.of(Kind.PERSONAL_DATA, AuditOutcome.MASKED, "Personal data replaced in the answer"));
        }
        String limited = limitRecords(text, ctx.maxRecords());
        if (!limited.equals(text)) {
            text = limited;
            decisions.add(Decision.of(Kind.RECORD_LIMIT, AuditOutcome.ALLOWED, "Answer shortened to " + ctx.maxRecords() + " records"));
        }
        if (notes != null && !notes.isEmpty()) {
            StringBuilder sb = new StringBuilder(text);
            for (String note : notes) {
                sb.append("\n\nNote: ").append(note);
            }
            text = sb.toString();
        }
        return new OutputResult(text, decisions);
    }

    // ----- retrieved passages -----

    public record PassageResult(String text, boolean flagged) {
    }

    /**
     * Retrieved text is data, never instructions. With hidden-instruction protection on, lines that look like commands to
     * the model are removed and the passage is reported as flagged.
     */
    public static PassageResult filterPassage(String passage, boolean protectionOn) {
        if (!protectionOn || passage == null || !INSTRUCTION_LIKE.matcher(passage).find()) {
            return new PassageResult(passage == null ? "" : passage, false);
        }
        StringBuilder kept = new StringBuilder();
        for (String line : passage.split("\\R")) {
            if (!INSTRUCTION_LIKE.matcher(line).find()) {
                kept.append(line).append('\n');
            }
        }
        return new PassageResult(kept.toString().strip(), true);
    }

    // ----- helpers -----

    public static boolean containsPersonalData(String text) {
        return EMAIL.matcher(text).find() || IBAN.matcher(text).find() || PHONE.matcher(text).find();
    }

    public static String maskPersonalData(String text) {
        String masked = replace(EMAIL, text, "[email]");
        masked = replace(IBAN, masked, "[IBAN]");
        return replace(PHONE, masked, "[phone]");
    }

    private static String replace(Pattern p, String text, String replacement) {
        Matcher m = p.matcher(text);
        return m.replaceAll(Matcher.quoteReplacement(replacement));
    }

    /** Keeps at most {@code max} list items or table rows and tells the reader that the rest was left out. */
    static String limitRecords(String text, int max) {
        String[] lines = text.split("\\R", -1);
        int count = 0;
        boolean cut = false;
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean separator = TABLE_ROW.matcher(line).matches() && TABLE_SEPARATOR.matcher(line).matches();
            boolean header = TABLE_ROW.matcher(line).matches() && !separator && i + 1 < lines.length
                    && TABLE_ROW.matcher(lines[i + 1]).matches() && TABLE_SEPARATOR.matcher(lines[i + 1]).matches();
            boolean record = LIST_LINE.matcher(line).matches() || (TABLE_ROW.matcher(line).matches() && !separator && !header);
            if (record && ++count > max) {
                cut = true;
                continue;
            }
            kept.add(line);
        }
        if (!cut) {
            return text;
        }
        return String.join("\n", kept).stripTrailing() + "\n\n(Showing the first " + max + " records.)";
    }
}
