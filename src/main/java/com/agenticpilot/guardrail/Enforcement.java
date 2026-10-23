package com.agenticpilot.guardrail;

/**
 * How a guardrail is enforced, so people can tell hard stops from wishes. PLATFORM: enforced in code, the model cannot bypass it.
 * MODEL: only an instruction in the prompt, the model may ignore it. RECORDED_ONLY: stored and audited but not enforced yet.
 */
public enum Enforcement {
    PLATFORM, MODEL, RECORDED_ONLY;

    /** Every control and setting that can be labelled. */
    public enum Topic {
        PERSONAL_DATA, SPECIAL_DATA, REGION, RETENTION, HIDDEN_INSTRUCTIONS, APPROVALS, MAX_RECORDS, PURPOSE, HUMAN_REVIEW, AUDIT_LOG, CUSTOM_RULES
    }

    public static Enforcement of(Topic topic) {
        return switch (topic) {
            case PERSONAL_DATA, SPECIAL_DATA, REGION, RETENTION, HIDDEN_INSTRUCTIONS, APPROVALS, MAX_RECORDS, AUDIT_LOG, CUSTOM_RULES -> PLATFORM;
            case PURPOSE -> MODEL;
            case HUMAN_REVIEW -> RECORDED_ONLY;
        };
    }

    /** The topic of a score control. */
    public static Topic topicOf(Control control) {
        return switch (control) {
            case PERSONAL_DATA -> Topic.PERSONAL_DATA;
            case SPECIAL_DATA -> Topic.SPECIAL_DATA;
            case REGION -> Topic.REGION;
            case RETENTION -> Topic.RETENTION;
            case HIDDEN_INSTRUCTIONS -> Topic.HIDDEN_INSTRUCTIONS;
            case APPROVALS -> Topic.APPROVALS;
            case PURPOSE -> Topic.PURPOSE;
        };
    }
}
