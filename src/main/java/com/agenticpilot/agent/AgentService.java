package com.agenticpilot.agent;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.AgentGuardrailRepository;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.user.AppUser;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AgentService {

    public static final int NAME_MAX = 40;
    public static final int JOB_MAX = 200;
    public static final int INSTRUCTIONS_MAX = 8000;

    private final AgentRepository agents;
    private final SkillRepository skills;
    private final DataSourceRepository sources;
    private final CurrentUser currentUser;
    private final AgentGuardrailRepository guardrails;
    private final WorkspacePolicyService policies;
    private final AuditService audit;

    public AgentService(AgentRepository agents, SkillRepository skills, DataSourceRepository sources, CurrentUser currentUser,
                        AgentGuardrailRepository guardrails, WorkspacePolicyService policies, AuditService audit) {
        this.agents = agents;
        this.skills = skills;
        this.sources = sources;
        this.currentUser = currentUser;
        this.guardrails = guardrails;
        this.policies = policies;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Agent> search(AgentStatus status, String query) {
        return agents.search(status, normalize(query));
    }

    @Transactional(readOnly = true)
    public Agent get(UUID id) {
        return agents.findById(id).orElseThrow(() -> new NotFoundException("Agent"));
    }

    @Transactional(readOnly = true)
    public boolean canEdit(Agent agent) {
        return currentUser.canEdit(agent.getOwner());
    }

    @Transactional(readOnly = true)
    public List<Agent> usingSkill(UUID skillId) {
        return agents.findBySkills_IdOrderByName(skillId);
    }

    @Transactional(readOnly = true)
    public List<Agent> usingSource(UUID sourceId) {
        return agents.findBySources_IdOrderByName(sourceId);
    }

    /** Creates an agent owned by the signed-in user. */
    public Agent create(String name, String job, Collection<UUID> skillIds, Collection<UUID> sourceIds) {
        AppUser me = currentUser.require();
        Agent agent = new Agent(validName(name), me);
        agent.setJob(validJob(job));
        skills.findAllById(skillIds).forEach(s -> agent.getSkills().add(s));
        sources.findAllById(sourceIds).forEach(s -> agent.getSources().add(s));
        agents.save(agent);
        guardrails.save(AgentGuardrail.defaultsFor(agent, policies.current().getDefaultRetention()));
        audit.record(agent.getId(), agent.getName(), "Agent created", AuditOutcome.ALLOWED, null, Map.of("owner", me.getUsername()));
        return agent;
    }

    public Agent update(UUID id, String name, String job, String instructions, Autonomy autonomy, ModelTier model) {
        Agent agent = editable(id);
        agent.setName(validName(name));
        agent.setJob(validJob(job));
        if (instructions != null && instructions.length() > INSTRUCTIONS_MAX) {
            throw new InvalidInputException("error.tooLong", INSTRUCTIONS_MAX);
        }
        agent.setInstructions(instructions == null ? "" : instructions);
        agent.setAutonomy(autonomy);
        agent.setModelTier(model);
        audit.record(agent.getId(), agent.getName(), "Agent settings saved", AuditOutcome.ALLOWED, null, Map.of());
        return agent;
    }

    public Agent setStatus(UUID id, AgentStatus status) {
        Agent agent = editable(id);
        agent.setStatus(status);
        audit.record(agent.getId(), agent.getName(), status == AgentStatus.ACTIVE ? "Agent activated" : "Agent paused", AuditOutcome.ALLOWED, null, Map.of());
        return agent;
    }

    public Agent addSkill(UUID id, UUID skillId) {
        Agent agent = editable(id);
        Skill skill = skills.findById(skillId).orElseThrow(() -> new NotFoundException("Skill"));
        agent.getSkills().add(skill);
        audit.record(agent.getId(), agent.getName(), "Skill \u201c" + skill.getName() + "\u201d added", AuditOutcome.ALLOWED, null, Map.of());
        return agent;
    }

    public Agent removeSkill(UUID id, UUID skillId) {
        Agent agent = editable(id);
        agent.getSkills().removeIf(s -> s.getId().equals(skillId));
        audit.record(agent.getId(), agent.getName(), "Skill removed", AuditOutcome.ALLOWED, null, Map.of("skillId", skillId.toString()));
        return agent;
    }

    public Agent addSource(UUID id, UUID sourceId) {
        Agent agent = editable(id);
        DataSource source = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
        agent.getSources().add(source);
        audit.record(agent.getId(), agent.getName(), "Data source \u201c" + source.getName() + "\u201d attached", AuditOutcome.ALLOWED, null, Map.of());
        return agent;
    }

    public Agent removeSource(UUID id, UUID sourceId) {
        Agent agent = editable(id);
        agent.getSources().removeIf(s -> s.getId().equals(sourceId));
        audit.record(agent.getId(), agent.getName(), "Data source detached", AuditOutcome.ALLOWED, null, Map.of("sourceId", sourceId.toString()));
        return agent;
    }

    private Agent editable(UUID id) {
        Agent agent = get(id);
        currentUser.requireEdit(agent.getOwner());
        return agent;
    }

    private static String validName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new InvalidInputException("error.nameRequired");
        }
        if (n.length() > NAME_MAX) {
            throw new InvalidInputException("error.tooLong", NAME_MAX);
        }
        return n;
    }

    private static String validJob(String job) {
        String j = job == null ? "" : job.strip();
        if (j.length() > JOB_MAX) {
            throw new InvalidInputException("error.tooLong", JOB_MAX);
        }
        return j;
    }

    private static String normalize(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }
}
