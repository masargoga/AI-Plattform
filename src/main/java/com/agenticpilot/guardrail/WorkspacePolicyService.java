package com.agenticpilot.guardrail;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.user.AppUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace policies. Only Admins change them. Every change is kept as a version (history) but needs no approval.
 * A policy that is switched on removes the loosest option from every agent; agents that break it are adjusted.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class WorkspacePolicyService {

    /** The policy after an update and the names of the agents that had to be adjusted to it. */
    public record Update(WorkspacePolicy policy, List<String> adjustedAgents) {
    }

    private final WorkspacePolicyRepository policies;
    private final WorkspacePolicyVersionRepository versions;
    private final AgentGuardrailRepository guardrails;
    private final com.agenticpilot.agent.AgentRepository agents;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public WorkspacePolicyService(WorkspacePolicyRepository policies, WorkspacePolicyVersionRepository versions,
                                  AgentGuardrailRepository guardrails, com.agenticpilot.agent.AgentRepository agents,
                                  AuditService audit, CurrentUser currentUser) {
        this.policies = policies;
        this.versions = versions;
        this.guardrails = guardrails;
        this.agents = agents;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public WorkspacePolicy current() {
        return policies.findById(WorkspacePolicy.ID).orElseThrow(() -> new IllegalStateException("Workspace policy row is missing"));
    }

    @Transactional(readOnly = true)
    public List<WorkspacePolicyVersion> history() {
        return versions.findAllByOrderByRevisionDesc();
    }

    public Update update(boolean personalDataOn, boolean specialDataOn, boolean euOnlyOn, Retention defaultRetention) {
        AppUser me = currentUser.requireAdmin();
        WorkspacePolicy policy = current();
        if (policy.isPersonalDataOn() == personalDataOn && policy.isSpecialDataOn() == specialDataOn
                && policy.isEuOnlyOn() == euOnlyOn && policy.getDefaultRetention() == defaultRetention) {
            return new Update(policy, List.of());
        }
        policy.apply(personalDataOn, specialDataOn, euOnlyOn, defaultRetention, me);
        versions.save(new WorkspacePolicyVersion(policy));
        audit.workspace("Workspace rules changed by " + me.getDisplayName() + " (v" + policy.getRevision() + ")", AuditOutcome.ALLOWED, null,
                Map.of("revision", policy.getRevision(), "personalData", personalDataOn, "specialData", specialDataOn,
                        "euOnly", euOnlyOn, "defaultRetention", defaultRetention.name()));

        List<String> adjusted = new ArrayList<>();
        for (AgentGuardrail g : guardrails.findAll()) {
            boolean changed = false;
            if (personalDataOn && g.getPersonalData() == PersonalDataMode.ALLOW) {
                g.setPersonalData(PersonalDataMode.MASK);
                changed = true;
            }
            if (specialDataOn && g.getSpecialData() == SpecialDataMode.ALLOW) {
                g.setSpecialData(SpecialDataMode.BLOCK);
                changed = true;
            }
            if (euOnlyOn && g.getRegion() == Region.ANY) {
                g.setRegion(Region.EU);
                changed = true;
            }
            if (changed) {
                var agent = agents.findById(g.getAgentId()).orElse(null);
                String name = agent == null ? null : agent.getName();
                adjusted.add(name == null ? g.getAgentId().toString() : name);
                audit.record(g.getAgentId(), name, "Guardrails adjusted to the workspace rules", AuditOutcome.ALLOWED, null,
                        Map.of("revision", policy.getRevision()));
            }
        }
        return new Update(policy, adjusted);
    }
}
