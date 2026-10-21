package com.agenticpilot.runtime;

import com.agenticpilot.guardrail.AgentGuardContext;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.CatchPattern;
import com.agenticpilot.guardrail.CustomRuleRepository;
import com.agenticpilot.guardrail.RuleSpec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds the engine's view of an agent: its built-in controls plus the live custom rules, in order of creation. */
@Component
public class GuardrailContextFactory {

    private final CustomRuleRepository rules;

    public GuardrailContextFactory(CustomRuleRepository rules) {
        this.rules = rules;
    }

    public AgentGuardContext build(AgentGuardrail g) {
        List<RuleSpec> specs = new ArrayList<>();
        rules.findAllByOrderByCreatedAtAsc().stream().filter(r -> r.appliesTo(g.getAgentId())).forEach(r ->
                CatchPattern.parse(r.getCatches()).ifPresent(p -> specs.add(new RuleSpec(r.getId(), r.getName(), p, r.getAction(), r.getMessage()))));
        return new AgentGuardContext(g.getPersonalData(), g.getSpecialData(), g.getApprovals(), g.getMaxRecords(), g.isHiddenInstructionProtection(), specs);
    }
}
