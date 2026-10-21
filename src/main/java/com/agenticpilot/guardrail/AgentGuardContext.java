package com.agenticpilot.guardrail;

import java.util.List;
import java.util.Set;

/** Everything the engine needs to know about an agent's guardrails. Custom rules are in order of creation. */
public record AgentGuardContext(PersonalDataMode personalData, SpecialDataMode specialData, Set<ApprovalAction> approvals,
                                int maxRecords, boolean hiddenInstructionProtection, List<RuleSpec> rules) {

    /** A strict context for work that is not tied to an agent: personal data masked, special categories blocked. */
    public static AgentGuardContext strict() {
        return new AgentGuardContext(PersonalDataMode.MASK, SpecialDataMode.BLOCK, Set.of(), 50, true, List.of());
    }
}
