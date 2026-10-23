package com.agenticpilot.agent;

import com.agenticpilot.api.ApiKeyRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.governance.GovernedService;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.AgentGuardrailRepository;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.governance.Lifecycle;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.user.AppUser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agents with an approval lifecycle (see {@link GovernedService}). The agent's own fields are the live, approved version;
 * edits to its configuration go into a draft. Pausing and activating stay immediate. Guardrail controls are not versioned.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AgentService extends GovernedService<Agent, AgentVersion, AgentService.Content> {

    public static final int NAME_MAX = 40;
    public static final int JOB_MAX = 200;
    public static final int INSTRUCTIONS_MAX = 8000;

    /** The configuration of an agent, also what a version stores. */
    public record Content(String name, String job, String instructions, Autonomy autonomy, ModelTier modelTier,
                          Set<UUID> skillIds, Set<UUID> sourceIds) {
    }

    private final AgentRepository agents;
    private final AgentVersionRepository agentVersions;
    private final SkillRepository skills;
    private final DataSourceRepository sources;
    private final AgentGuardrailRepository guardrails;
    private final WorkspacePolicyService policies;
    private final ApiKeyRepository apiKeys;

    public AgentService(AgentRepository agents, AgentVersionRepository agentVersions, SkillRepository skills, DataSourceRepository sources,
                        CurrentUser currentUser, AgentGuardrailRepository guardrails, WorkspacePolicyService policies, AuditService audit,
                        ApiKeyRepository apiKeys) {
        super(agents, agentVersions, currentUser, audit, Content.class, "Agent");
        this.agents = agents;
        this.agentVersions = agentVersions;
        this.skills = skills;
        this.sources = sources;
        this.guardrails = guardrails;
        this.policies = policies;
        this.apiKeys = apiKeys;
    }

    @Transactional(readOnly = true)
    public List<Agent> search(AgentStatus status, String query) {
        return agents.search(status, normalize(query));
    }

    @Transactional(readOnly = true)
    @Override
    public Agent get(UUID id) {
        return super.get(id);
    }

    @Transactional(readOnly = true)
    public boolean canEdit(Agent agent) {
        return currentUser.canEdit(agent.getOwner());
    }

    /** Agents that use the skill in their live (approved) configuration. */
    @Transactional(readOnly = true)
    public List<Agent> usingSkill(UUID skillId) {
        return agents.findBySkills_IdOrderByName(skillId);
    }

    @Transactional(readOnly = true)
    public List<Agent> usingSource(UUID sourceId) {
        return agents.findBySources_IdOrderByName(sourceId);
    }

    /** Creates a managed agent owned by the signed-in user. */
    public Agent create(String name, String job, Collection<UUID> skillIds, Collection<UUID> sourceIds) {
        return create(name, job, skillIds, sourceIds, AgentKind.MANAGED);
    }

    /**
     * Creates an agent as a draft (version 1) owned by the signed-in user. It runs in the test chat as a draft, but is not
     * usable through the API until another Admin approves it. An external agent only has its text checked; it has no skills or sources.
     */
    public Agent create(String name, String job, Collection<UUID> skillIds, Collection<UUID> sourceIds, AgentKind kind) {
        AppUser me = currentUser.require();
        Agent agent = new Agent(validName(name), me, kind == null ? AgentKind.MANAGED : kind);
        agent.setJob(validJob(job));
        Set<UUID> skillSet = new LinkedHashSet<>();
        Set<UUID> sourceSet = new LinkedHashSet<>();
        if (agent.getKind() == AgentKind.MANAGED) {
            skills.findAllById(skillIds).forEach(s -> {
                agent.getSkills().add(s);
                skillSet.add(s.getId());
            });
            sources.findAllById(sourceIds).forEach(s -> {
                agent.getSources().add(s);
                sourceSet.add(s.getId());
            });
        }
        agents.save(agent);
        agentVersions.save(newVersion(agent.getId(), 1, toJson(snapshotOf(agent)), me));
        guardrails.save(AgentGuardrail.defaultsFor(agent, policies.current().getDefaultRetention()));
        audit.record(agent.getId(), agent.getName(), "Agent created as a draft", AuditOutcome.ALLOWED, null, Map.of("owner", me.getUsername(), "kind", agent.getKind().name()));
        return agent;
    }

    /** Stores the settings in the draft; the live version stays active until the draft is approved. */
    public Agent update(UUID id, String name, String job, String instructions, Autonomy autonomy, ModelTier model) {
        Content current = editable(id);
        if (instructions != null && instructions.length() > INSTRUCTIONS_MAX) {
            throw new InvalidInputException("error.tooLong", INSTRUCTIONS_MAX);
        }
        return saveDraftInternal(id, new Content(validName(name), validJob(job), instructions == null ? "" : instructions, autonomy, model,
                current.skillIds(), current.sourceIds()));
    }

    /** Pause and activate take effect at once. Only an approved agent can be active. */
    public Agent setStatus(UUID id, AgentStatus status) {
        Agent agent = get(id);
        currentUser.requireEdit(agent.getOwner());
        if (status == AgentStatus.ACTIVE && agent.getLifecycle() != Lifecycle.APPROVED) {
            throw new InvalidInputException("error.agent.notApproved");
        }
        agent.setStatus(status);
        audit.record(agent.getId(), agent.getName(), status == AgentStatus.ACTIVE ? "Agent activated" : "Agent paused", AuditOutcome.ALLOWED, null, Map.of());
        return agent;
    }

    public Agent addSkill(UUID id, UUID skillId) {
        skills.findById(skillId).orElseThrow(() -> new NotFoundException("Skill"));
        return changeSets(id, c -> c.skillIds().add(skillId), null);
    }

    public Agent removeSkill(UUID id, UUID skillId) {
        return changeSets(id, c -> c.skillIds().remove(skillId), null);
    }

    public Agent addSource(UUID id, UUID sourceId) {
        sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
        return changeSets(id, c -> c.sourceIds().add(sourceId), null);
    }

    public Agent removeSource(UUID id, UUID sourceId) {
        return changeSets(id, c -> c.sourceIds().remove(sourceId), null);
    }

    private Agent changeSets(UUID id, java.util.function.Consumer<Content> change, Void unused) {
        Content current = editable(id);
        Content copy = new Content(current.name(), current.job(), current.instructions(), current.autonomy(), current.modelTier(),
                new LinkedHashSet<>(current.skillIds()), new LinkedHashSet<>(current.sourceIds()));
        change.accept(copy);
        return saveDraftInternal(id, copy);
    }

    @Override
    protected Content snapshotOf(Agent a) {
        return contentOf(a);
    }

    public static Content contentOf(Agent a) {
        return new Content(a.getName(), a.getJob(), a.getInstructions(), a.getAutonomy(), a.getModelTier(),
                a.getSkills().stream().map(Skill::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                a.getSources().stream().map(DataSource::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    @Override
    protected void apply(Agent agent, Content c) {
        agent.setName(c.name());
        agent.setJob(c.job() == null ? "" : c.job());
        agent.setInstructions(c.instructions() == null ? "" : c.instructions());
        agent.setAutonomy(c.autonomy() == null ? Autonomy.ASK : c.autonomy());
        agent.setModelTier(c.modelTier() == null ? ModelTier.BALANCED : c.modelTier());
        if (agent.getKind() == AgentKind.MANAGED) {
            agent.getSkills().clear();
            skills.findAllById(c.skillIds() == null ? Set.<UUID>of() : c.skillIds()).forEach(s -> agent.getSkills().add(s));
            agent.getSources().clear();
            sources.findAllById(c.sourceIds() == null ? Set.<UUID>of() : c.sourceIds()).forEach(s -> agent.getSources().add(s));
        }
    }

    @Override
    protected AgentVersion newVersion(UUID itemId, int number, String json, AppUser author) {
        return new AgentVersion(itemId, number, json, author);
    }

    @Override
    protected String nameOf(Content c) {
        return c.name();
    }

    /** An agent may only use skills that are approved. */
    @Override
    protected void checkReady(Agent agent, Content c) {
        if (agent.getKind() != AgentKind.MANAGED || c.skillIds() == null) {
            return;
        }
        List<String> notReady = new ArrayList<>();
        for (UUID skillId : c.skillIds()) {
            skills.findById(skillId).ifPresentOrElse(s -> {
                if (!s.isUsable()) {
                    notReady.add(s.getName());
                }
            }, () -> notReady.add("?"));
        }
        if (!notReady.isEmpty()) {
            throw new InvalidInputException("error.agent.skillNotApproved", String.join(", ", notReady));
        }
    }

    @Override
    protected void checkUnused(Agent agent) {
        if (apiKeys.findByAgentIdOrderByCreatedAtDesc(agent.getId()).stream().anyMatch(k -> !k.isRevoked())) {
            throw new InvalidInputException("error.agent.hasKeys");
        }
    }

    @Override
    protected void record(Agent agent, String event, AuditOutcome outcome, Map<String, ?> details) {
        audit.record(agent.getId(), agent.getName(), "Agent " + event, outcome, null, details);
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
