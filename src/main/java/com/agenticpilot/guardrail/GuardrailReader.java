package com.agenticpilot.guardrail;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.common.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads an agent's guardrail settings without a user permission check, for the runtime (chat pipeline, guardrail check),
 * which acts for a signed-in user or an API key and has already authenticated its caller. An agent without a row gets the
 * recommended defaults.
 */
@Component
public class GuardrailReader {

    private final AgentGuardrailRepository guardrails;
    private final AgentRepository agents;
    private final WorkspacePolicyRepository policies;

    public GuardrailReader(AgentGuardrailRepository guardrails, AgentRepository agents, WorkspacePolicyRepository policies) {
        this.guardrails = guardrails;
        this.agents = agents;
        this.policies = policies;
    }

    @Transactional
    public AgentGuardrail get(UUID agentId) {
        return guardrails.findById(agentId).orElseGet(() -> {
            Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
            Retention retention = policies.findById(WorkspacePolicy.ID).map(WorkspacePolicy::getDefaultRetention).orElse(Retention.DAYS_90);
            return guardrails.save(AgentGuardrail.defaultsFor(agent, retention));
        });
    }
}
