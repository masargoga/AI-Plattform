package com.agenticpilot.guardrail;

import com.agenticpilot.audit.AuditOutcome;
import java.util.UUID;

/**
 * One thing the guardrail engine did to a request. It names the category and the rule, never the personal value that
 * triggered it, so decisions are safe to log.
 */
public record Decision(Kind kind, AuditOutcome outcome, UUID ruleId, String ruleName, String detail) {

    public enum Kind { SPECIAL_DATA, PERSONAL_DATA, APPROVAL_ACTION, CUSTOM_RULE, HIDDEN_INSTRUCTION, RECORD_LIMIT }

    public static Decision of(Kind kind, AuditOutcome outcome, String detail) {
        return new Decision(kind, outcome, null, null, detail);
    }
}
