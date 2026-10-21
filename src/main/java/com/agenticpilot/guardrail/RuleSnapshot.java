package com.agenticpilot.guardrail;

import java.util.Set;
import java.util.UUID;

/** The editable fields of a rule, as stored in a version. */
public record RuleSnapshot(String name, String catches, RuleAction action, String message, RuleScope scope,
                           Set<UUID> agentIds, String sample) {
}
