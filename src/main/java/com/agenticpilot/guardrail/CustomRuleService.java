package com.agenticpilot.guardrail;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.user.AppUser;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Custom rules with versions and four-eyes approval. Every change creates a pending version; the live rule changes only
 * when a different Admin approves it. All checks run here on the server, not only in the UI.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class CustomRuleService {

    public static final int NAME_MAX = 48;
    public static final int NOTE_MAX = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What a user submits for a rule. */
    public record RuleFields(String name, String catches, RuleAction action, String message, RuleScope scope,
                             Set<UUID> agentIds, String sample) {
    }

    private final CustomRuleRepository rules;
    private final RuleVersionRepository versions;
    private final AgentRepository agents;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public CustomRuleService(CustomRuleRepository rules, RuleVersionRepository versions, AgentRepository agents,
                             AuditService audit, CurrentUser currentUser) {
        this.rules = rules;
        this.versions = versions;
        this.agents = agents;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    // ----- reading -----

    @Transactional(readOnly = true)
    public List<CustomRule> all() {
        return rules.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public CustomRule get(UUID id) {
        return rules.findById(id).orElseThrow(() -> new NotFoundException("Rule"));
    }

    /** Rules that apply to the agent: those for all agents and those that name it (live or not). */
    @Transactional(readOnly = true)
    public List<CustomRule> forAgent(UUID agentId) {
        return all().stream().filter(r -> r.getScope() == RuleScope.ALL || r.getAgentIds().contains(agentId)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<RuleVersion> pending(UUID ruleId) {
        return versions.findByRuleIdAndStatus(ruleId, VersionStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<RuleVersion> allPending() {
        return versions.findByStatus(VersionStatus.PENDING);
    }

    /** Approved and rejected versions, newest first. */
    @Transactional(readOnly = true)
    public List<RuleVersion> history(UUID ruleId) {
        return versions.findByRuleIdAndStatusInOrderByVersionNumberDesc(ruleId, Set.of(VersionStatus.APPROVED, VersionStatus.REJECTED));
    }

    public RuleSnapshot snapshot(RuleVersion version) {
        return JSON.readValue(version.getSnapshotJson(), RuleSnapshot.class);
    }

    /** Admins may edit every rule. An Agent owner may edit rules for selected agents when they own all of those agents. */
    @Transactional(readOnly = true)
    public boolean canEdit(CustomRule rule) {
        AppUser me = currentUser.require();
        return me.isAdmin() || (rule.getScope() == RuleScope.SELECTED && !rule.getAgentIds().isEmpty() && ownsAll(me, rule.getAgentIds()));
    }

    // ----- proposing changes -----

    /** Proposes a new rule (ruleId null) or a change to an existing one. Nothing goes live until it is approved. */
    public CustomRule propose(UUID ruleId, RuleFields fields, String note) {
        AppUser me = currentUser.require();
        RuleSnapshot snapshot = validate(me, fields);
        String why = requireNote(note);

        CustomRule rule;
        int versionNumber;
        if (ruleId == null) {
            rule = rules.save(new CustomRule(snapshot, me));
            versionNumber = 1;
        } else {
            rule = get(ruleId);
            if (!canEdit(rule)) {
                throw new AccessDeniedException("Not allowed to change this rule");
            }
            if (pending(ruleId).isPresent()) {
                throw new InvalidInputException("error.rule.pendingExists");
            }
            versionNumber = nextVersionNumber(ruleId);
        }
        submit(rule, versionNumber, snapshot, me, why);
        return rule;
    }

    /** Adding or removing an agent is a change that needs approval like any other. */
    public CustomRule proposeAgentAssignment(UUID ruleId, UUID agentId, boolean assigned) {
        AppUser me = currentUser.require();
        CustomRule rule = get(ruleId);
        if (rule.getScope() != RuleScope.SELECTED) {
            throw new InvalidInputException("error.rule.allAgents");
        }
        if (!canEdit(rule)) {
            throw new AccessDeniedException("Not allowed to change this rule");
        }
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        Set<UUID> next = new LinkedHashSet<>(rule.getAgentIds());
        if (assigned) {
            if (!me.isAdmin() && !agent.getOwner().getId().equals(me.getId())) {
                throw new AccessDeniedException("Not your agent");
            }
            next.add(agentId);
        } else {
            next.remove(agentId);
        }
        RuleSnapshot live = rule.toSnapshot();
        RuleFields fields = new RuleFields(live.name(), live.catches(), live.action(), live.message(), RuleScope.SELECTED, next, live.sample());
        return propose(ruleId, fields, agent.getName() + (assigned ? " added to the rule" : " removed from the rule"));
    }

    // ----- deciding -----

    /** A different Admin approves: the proposed version becomes the live rule. */
    public CustomRule approve(UUID ruleId) {
        AppUser me = currentUser.requireAdmin();
        CustomRule rule = get(ruleId);
        RuleVersion version = pending(ruleId).orElseThrow(() -> new InvalidInputException("error.rule.nothingPending"));
        requireOtherPerson(me, version);
        version.decide(VersionStatus.APPROVED, me, Instant.now());
        rule.applySnapshot(snapshot(version));
        rule.setLiveVersion(version.getVersionNumber());
        versions.flush();
        audit.workspace("Guardrail “" + rule.getName() + "” v" + version.getVersionNumber() + " approved by " + me.getDisplayName(),
                AuditOutcome.ALLOWED, rule.getId(), Map.of("version", version.getVersionNumber(), "author", version.getAuthor().getUsername()));
        return rule;
    }

    /** A different Admin rejects. A rule that never went live is removed. */
    public void reject(UUID ruleId) {
        AppUser me = currentUser.requireAdmin();
        CustomRule rule = get(ruleId);
        RuleVersion version = pending(ruleId).orElseThrow(() -> new InvalidInputException("error.rule.nothingPending"));
        requireOtherPerson(me, version);
        version.decide(VersionStatus.REJECTED, me, Instant.now());
        audit.workspace("Change to “" + rule.getName() + "” rejected by " + me.getDisplayName(), AuditOutcome.BLOCKED, rule.getId(),
                Map.of("version", version.getVersionNumber(), "author", version.getAuthor().getUsername()));
        dropIfNeverLive(rule);
    }

    /** Only the author can withdraw a pending change. */
    public void withdraw(UUID ruleId) {
        AppUser me = currentUser.require();
        CustomRule rule = get(ruleId);
        RuleVersion version = pending(ruleId).orElseThrow(() -> new InvalidInputException("error.rule.nothingPending"));
        if (!version.getAuthor().getId().equals(me.getId())) {
            throw new AccessDeniedException("Only the author can withdraw this change");
        }
        version.decide(VersionStatus.WITHDRAWN, me, Instant.now());
        audit.workspace("Change to “" + rule.getName() + "” withdrawn by " + me.getDisplayName(), AuditOutcome.ALLOWED, rule.getId(),
                Map.of("version", version.getVersionNumber()));
        dropIfNeverLive(rule);
    }

    // ----- immediate, Admin only -----

    public void setEnabled(UUID ruleId, boolean enabled) {
        AppUser me = currentUser.requireAdmin();
        CustomRule rule = get(ruleId);
        rule.setEnabled(enabled);
        audit.workspace("Guardrail “" + rule.getName() + "” turned " + (enabled ? "on" : "off") + " by " + me.getDisplayName(),
                AuditOutcome.ALLOWED, rule.getId(), Map.of("enabled", enabled));
    }

    public void delete(UUID ruleId) {
        AppUser me = currentUser.requireAdmin();
        CustomRule rule = get(ruleId);
        String name = rule.getName();
        rules.delete(rule);
        audit.workspace("Guardrail “" + name + "” deleted by " + me.getDisplayName(), AuditOutcome.ALLOWED, ruleId, Map.of());
    }

    // ----- internals -----

    private void submit(CustomRule rule, int versionNumber, RuleSnapshot snapshot, AppUser author, String note) {
        versions.save(new RuleVersion(rule.getId(), versionNumber, JSON.writeValueAsString(snapshot), author, note));
        audit.workspace("Change to “" + snapshot.name() + "” proposed by " + author.getDisplayName() + " (v" + versionNumber + ")",
                AuditOutcome.NEEDS_APPROVAL, rule.getId(), Map.of("version", versionNumber));
    }

    private void requireOtherPerson(AppUser me, RuleVersion version) {
        if (version.getAuthor().getId().equals(me.getId())) {
            throw new AccessDeniedException("A second person has to decide on your change");
        }
    }

    private void dropIfNeverLive(CustomRule rule) {
        if (rule.getLiveVersion() == 0) {
            rules.delete(rule);
        }
    }

    private int nextVersionNumber(UUID ruleId) {
        return versions.findTopByRuleIdOrderByVersionNumberDesc(ruleId).map(v -> v.getVersionNumber() + 1).orElse(1);
    }

    private boolean ownsAll(AppUser me, Set<UUID> agentIds) {
        return agentIds.stream().allMatch(id -> agents.findById(id).map(a -> a.getOwner().getId().equals(me.getId())).orElse(false));
    }

    private RuleSnapshot validate(AppUser me, RuleFields f) {
        String name = f.name() == null ? "" : f.name().strip();
        if (name.isEmpty()) {
            throw new InvalidInputException("error.rule.nameRequired");
        }
        if (name.length() > NAME_MAX) {
            throw new InvalidInputException("error.tooLong", NAME_MAX);
        }
        if (CatchPattern.parse(f.catches()).isEmpty()) {
            throw new InvalidInputException("error.rule.catchesInvalid");
        }
        if (f.action() == null || f.scope() == null) {
            throw new InvalidInputException("error.invalidChoice");
        }
        String message = f.message() == null ? "" : f.message().strip();
        String sample = f.sample() == null ? "" : f.sample().strip();
        if (message.length() > 500 || sample.length() > 500) {
            throw new InvalidInputException("error.tooLong", 500);
        }
        Set<UUID> agentIds = new LinkedHashSet<>();
        if (f.scope() == RuleScope.ALL) {
            if (!me.isAdmin()) {
                throw new AccessDeniedException("Only Admins can apply a rule to all agents");
            }
        } else {
            agentIds.addAll(f.agentIds() == null ? Set.of() : f.agentIds());
            if (agentIds.isEmpty()) {
                throw new InvalidInputException("error.rule.agentsRequired");
            }
            for (UUID id : agentIds) {
                Agent agent = agents.findById(id).orElseThrow(() -> new NotFoundException("Agent"));
                if (!me.isAdmin() && !agent.getOwner().getId().equals(me.getId())) {
                    throw new AccessDeniedException("You can only propose rules for your own agents");
                }
            }
        }
        return new RuleSnapshot(name, f.catches().strip(), f.action(), message, f.scope(), agentIds, sample);
    }

    private static String requireNote(String note) {
        String n = note == null ? "" : note.strip();
        if (n.isEmpty()) {
            throw new InvalidInputException("error.rule.noteRequired");
        }
        if (n.length() > NOTE_MAX) {
            throw new InvalidInputException("error.tooLong", NOTE_MAX);
        }
        return n;
    }
}
