package com.agenticpilot.guardrail;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Built-in guardrail controls of an agent. Owners and Admins may change them, never below the workspace policy. */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AgentGuardrailService {

    private static final Set<ApprovalAction> RECOMMENDED_APPROVALS = EnumSet.of(ApprovalAction.EMAIL, ApprovalAction.REFUND);

    private final AgentGuardrailRepository guardrails;
    private final AgentRepository agents;
    private final WorkspacePolicyService policies;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public AgentGuardrailService(AgentGuardrailRepository guardrails, AgentRepository agents, WorkspacePolicyService policies,
                                 AuditService audit, CurrentUser currentUser) {
        this.guardrails = guardrails;
        this.agents = agents;
        this.policies = policies;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** The settings of an agent. An agent without a row gets the recommended defaults. */
    public AgentGuardrail get(UUID agentId) {
        return guardrails.findById(agentId).orElseGet(() -> {
            Agent agent = agent(agentId);
            return guardrails.save(AgentGuardrail.defaultsFor(agent, policies.current().getDefaultRetention()));
        });
    }

    @Transactional(readOnly = true)
    public GuardrailScore score(UUID agentId) {
        return GuardrailScore.of(guardrails.findById(agentId).map(AgentGuardrail::toSettings)
                .orElseGet(() -> AgentGuardrail.defaultsFor(agent(agentId), policies.current().getDefaultRetention()).toSettings()));
    }

    public void setPersonalData(UUID agentId, PersonalDataMode mode) {
        if (mode == PersonalDataMode.ALLOW && policies.current().isPersonalDataOn()) {
            throw new InvalidInputException("error.policy.personalData");
        }
        change(agentId, "Personal data set to " + label(mode), g -> g.setPersonalData(mode));
    }

    public void setSpecialData(UUID agentId, SpecialDataMode mode) {
        if (mode == SpecialDataMode.ALLOW && policies.current().isSpecialDataOn()) {
            throw new InvalidInputException("error.policy.specialData");
        }
        change(agentId, "Special-category data set to " + label(mode), g -> g.setSpecialData(mode));
    }

    public void setRegion(UUID agentId, Region region) {
        if (region == Region.ANY && policies.current().isEuOnlyOn()) {
            throw new InvalidInputException("error.policy.region");
        }
        change(agentId, "Processing location set to " + (region == Region.EU ? "EU only" : "anywhere"), g -> g.setRegion(region));
    }

    public void setRetention(UUID agentId, Retention retention) {
        change(agentId, "Retention set to " + label(retention), g -> g.setRetention(retention));
    }

    public void setMaxRecords(UUID agentId, int max) {
        if (max != 10 && max != 50 && max != 200) {
            throw new InvalidInputException("error.invalidChoice");
        }
        change(agentId, "Record limit set to " + max, g -> g.setMaxRecords(max));
    }

    public void setHiddenInstructionProtection(UUID agentId, boolean on) {
        change(agentId, "Hidden-instruction protection turned " + (on ? "on" : "off"), g -> g.setHiddenInstructionProtection(on));
    }

    public void setHumanReview(UUID agentId, boolean on) {
        change(agentId, "Human review for decisions about people turned " + (on ? "on" : "off"), g -> g.setHumanReview(on));
    }

    public void setApproval(UUID agentId, ApprovalAction action, boolean required) {
        change(agentId, "Approval for " + label(action) + (required ? " required" : " no longer required"), g -> {
            Set<ApprovalAction> next = g.getApprovals().isEmpty() ? EnumSet.noneOf(ApprovalAction.class) : EnumSet.copyOf(g.getApprovals());
            if (required) {
                next.add(action);
            } else {
                next.remove(action);
            }
            g.setApprovals(next);
        });
    }

    public void setPurpose(UUID agentId, String purpose) {
        if (purpose != null && purpose.length() > AgentGuardrail.PURPOSE_MAX) {
            throw new InvalidInputException("error.tooLong", AgentGuardrail.PURPOSE_MAX);
        }
        change(agentId, "Purpose updated", g -> g.setPurpose(purpose));
    }

    /** Applies the recommended value for a missing control. For the purpose there is no recommendation, so nothing changes. */
    public void fix(UUID agentId, Control control) {
        switch (control) {
            case PERSONAL_DATA -> setPersonalData(agentId, PersonalDataMode.MASK);
            case SPECIAL_DATA -> setSpecialData(agentId, SpecialDataMode.BLOCK);
            case REGION -> setRegion(agentId, Region.EU);
            case RETENTION -> setRetention(agentId, Retention.DAYS_90);
            case HIDDEN_INSTRUCTIONS -> setHiddenInstructionProtection(agentId, true);
            case APPROVALS -> change(agentId, "Approvals set to the recommended actions", g -> g.setApprovals(RECOMMENDED_APPROVALS));
            case PURPOSE -> { }
        }
    }

    private void change(UUID agentId, String event, Consumer<AgentGuardrail> mutation) {
        Agent agent = agent(agentId);
        currentUser.requireEdit(agent.getOwner());
        AgentGuardrail g = get(agentId);
        GuardrailScore before = GuardrailScore.of(g.toSettings());
        mutation.accept(g);
        GuardrailScore after = GuardrailScore.of(g.toSettings());
        audit.record(agentId, agent.getName(), event, AuditOutcome.ALLOWED, null,
                Map.of("scoreBefore", before.met(), "scoreAfter", after.met()));
    }

    private Agent agent(UUID id) {
        return agents.findById(id).orElseThrow(() -> new NotFoundException("Agent"));
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase().replace('_', ' ');
    }
}
