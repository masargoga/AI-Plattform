package com.agenticpilot.governance;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentVersion;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.RuleSnapshot;
import com.agenticpilot.guardrail.RuleVersion;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillVersion;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.user.AppUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that waits for a second person: custom rules, skills and agents. Lists it, explains each change field by field
 * and hands the decision to the service that owns the item. The four-eyes check is done by those services.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class ApprovalService {

    public enum ItemType { RULE, SKILL, AGENT }

    /** One entry of the Approvals list. For a rule, versionId is the id of the rule version. */
    public record ReviewItem(ItemType type, UUID itemId, UUID versionId, String name, int versionNumber, AppUser author, String note,
                             Instant submittedAt, boolean mine, boolean isNew) {
    }

    /** A changed field: the key of its label, the value before and after. Lists are joined with commas. */
    public record Change(String field, String before, String after) {
    }

    /** What the reviewer sees for one entry. */
    public record Detail(ReviewItem item, List<Change> changes, List<String> warnings) {
    }

    private final SkillService skills;
    private final AgentService agents;
    private final CustomRuleService rules;
    private final SkillRepository skillRepository;
    private final DataSourceRepository sourceRepository;
    private final CurrentUser currentUser;
    private final GovernanceWarnings warnings;

    public ApprovalService(SkillService skills, AgentService agents, CustomRuleService rules, SkillRepository skillRepository,
                           DataSourceRepository sourceRepository, CurrentUser currentUser, GovernanceWarnings warnings) {
        this.skills = skills;
        this.agents = agents;
        this.rules = rules;
        this.skillRepository = skillRepository;
        this.sourceRepository = sourceRepository;
        this.currentUser = currentUser;
        this.warnings = warnings;
    }

    @Transactional(readOnly = true)
    public List<ReviewItem> pending() {
        AppUser me = currentUser.require();
        List<ReviewItem> items = new ArrayList<>();
        for (RuleVersion v : rules.allPending()) {
            CustomRule rule = rules.get(v.getRuleId());
            items.add(new ReviewItem(ItemType.RULE, rule.getId(), v.getId(), rule.getName(), v.getVersionNumber(), v.getAuthor(), v.getNote(),
                    v.getCreatedAt(), v.getAuthor().getId().equals(me.getId()), rule.getLiveVersion() == 0));
        }
        for (SkillVersion v : skills.pendingVersions()) {
            Skill skill = skills.get(v.getItemId());
            items.add(new ReviewItem(ItemType.SKILL, skill.getId(), v.getId(), skills.parse(v).name(), v.getVersionNumber(), v.getAuthor(), v.getNote(),
                    v.getUpdatedAt(), v.getAuthor().getId().equals(me.getId()), skill.getLiveVersion() == 0));
        }
        for (AgentVersion v : agents.pendingVersions()) {
            Agent agent = agents.get(v.getItemId());
            items.add(new ReviewItem(ItemType.AGENT, agent.getId(), v.getId(), agents.parse(v).name(), v.getVersionNumber(), v.getAuthor(), v.getNote(),
                    v.getUpdatedAt(), v.getAuthor().getId().equals(me.getId()), agent.getLiveVersion() == 0));
        }
        items.sort(Comparator.comparing(ReviewItem::submittedAt));
        return items;
    }

    @Transactional(readOnly = true)
    public int pendingCount() {
        return rules.allPending().size() + skills.pendingCount() + agents.pendingCount();
    }

    @Transactional(readOnly = true)
    public Detail detail(ItemType type, UUID versionId) {
        ReviewItem item = pending().stream().filter(i -> i.type() == type && i.versionId().equals(versionId)).findFirst()
                .orElseThrow(() -> new com.agenticpilot.common.NotFoundException("Change"));
        return switch (type) {
            case SKILL -> {
                SkillVersion v = skills.version(versionId);
                SkillService.Content after = skills.parse(v);
                SkillService.Content before = SkillService.contentOf(skills.get(v.getItemId()));
                boolean isNew = item.isNew();
                yield new Detail(item, skillChanges(isNew ? null : before, after), List.of());
            }
            case AGENT -> {
                AgentVersion v = agents.version(versionId);
                AgentService.Content after = agents.parse(v);
                AgentService.Content before = AgentService.contentOf(agents.get(v.getItemId()));
                yield new Detail(item, agentChanges(item.isNew() ? null : before, after), warnings.forAgent(after));
            }
            case RULE -> {
                RuleSnapshot after = rules.snapshot(rules.pending(item.itemId()).orElseThrow());
                CustomRule rule = rules.get(item.itemId());
                yield new Detail(item, ruleChanges(item.isNew() ? null : rule.toSnapshot(), after), List.of());
            }
        };
    }

    /** What changed in a version compared with the version before it (everything for the first one). Skills and agents only. */
    @Transactional(readOnly = true)
    public List<Change> compare(ItemType type, UUID itemId, UUID versionId) {
        return switch (type) {
            case SKILL -> {
                List<SkillVersion> all = skills.history(itemId);
                int i = indexOf(all, versionId);
                SkillService.Content after = skills.parse(all.get(i));
                yield skillChanges(i + 1 < all.size() ? skills.parse(all.get(i + 1)) : null, after);
            }
            case AGENT -> {
                List<AgentVersion> all = agents.history(itemId);
                int i = indexOf(all, versionId);
                AgentService.Content after = agents.parse(all.get(i));
                yield agentChanges(i + 1 < all.size() ? agents.parse(all.get(i + 1)) : null, after);
            }
            case RULE -> throw new UnsupportedOperationException("Rules show their changes in their own sheet");
        };
    }

    private static int indexOf(List<? extends ItemVersion> all, UUID versionId) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(versionId)) {
                return i;
            }
        }
        throw new com.agenticpilot.common.NotFoundException("Version");
    }

    /** Approves or rejects (comment required) an entry. Skills and agents are decided here; rules by their own sheet. */
    public void approve(ItemType type, UUID versionId, String comment) {
        switch (type) {
            case SKILL -> skills.approve(versionId, comment);
            case AGENT -> agents.approve(versionId, comment);
            case RULE -> throw new UnsupportedOperationException("Rules are approved in their review sheet");
        }
    }

    public void reject(ItemType type, UUID versionId, String comment) {
        switch (type) {
            case SKILL -> skills.reject(versionId, comment);
            case AGENT -> agents.reject(versionId, comment);
            case RULE -> throw new UnsupportedOperationException("Rules are rejected in their review sheet");
        }
    }

    // ----- diffs -----

    List<Change> skillChanges(SkillService.Content before, SkillService.Content after) {
        List<Change> out = new ArrayList<>();
        add(out, "name", before == null ? null : before.name(), after.name());
        add(out, "type", before == null ? null : String.valueOf(before.type()), String.valueOf(after.type()));
        add(out, "description", before == null ? null : before.description(), after.description());
        add(out, "trigger", before == null ? null : before.triggerText(), after.triggerText());
        add(out, "instructions", before == null ? null : before.instructions(), after.instructions());
        add(out, "tools", before == null ? null : names(before.tools()), names(after.tools()));
        add(out, "format", before == null ? null : String.valueOf(before.outputFormat()), String.valueOf(after.outputFormat()));
        add(out, "compliance", before == null ? null : String.valueOf(before.complianceRelevant()), String.valueOf(after.complianceRelevant()));
        return out;
    }

    List<Change> agentChanges(AgentService.Content before, AgentService.Content after) {
        List<Change> out = new ArrayList<>();
        add(out, "name", before == null ? null : before.name(), after.name());
        add(out, "job", before == null ? null : before.job(), after.job());
        add(out, "instructions", before == null ? null : before.instructions(), after.instructions());
        add(out, "autonomy", before == null ? null : String.valueOf(before.autonomy()), String.valueOf(after.autonomy()));
        add(out, "model", before == null ? null : String.valueOf(before.modelTier()), String.valueOf(after.modelTier()));
        add(out, "skills", before == null ? null : skillNames(before.skillIds()), skillNames(after.skillIds()));
        add(out, "sources", before == null ? null : sourceNames(before.sourceIds()), sourceNames(after.sourceIds()));
        return out;
    }

    List<Change> ruleChanges(RuleSnapshot before, RuleSnapshot after) {
        List<Change> out = new ArrayList<>();
        add(out, "name", before == null ? null : before.name(), after.name());
        add(out, "catches", before == null ? null : before.catches(), after.catches());
        add(out, "action", before == null ? null : String.valueOf(before.action()), String.valueOf(after.action()));
        add(out, "message", before == null ? null : before.message(), after.message());
        add(out, "scope", before == null ? null : String.valueOf(before.scope()), String.valueOf(after.scope()));
        return out;
    }

    /** A change is listed when it is new (no earlier version) or when the value differs. */
    private static void add(List<Change> out, String field, String before, String after) {
        String b = before == null ? "" : before;
        String a = after == null ? "" : after;
        if (before == null ? !a.isBlank() && !a.equals("false") : !Objects.equals(b, a)) {
            out.add(new Change(field, b, a));
        }
    }

    private static String names(Set<?> values) {
        return values == null ? "" : values.stream().map(String::valueOf).sorted().collect(Collectors.joining(", "));
    }

    private String skillNames(Set<UUID> ids) {
        if (ids == null) {
            return "";
        }
        return skillRepository.findAllById(ids).stream().map(Skill::getName).sorted().collect(Collectors.joining(", "));
    }

    private String sourceNames(Set<UUID> ids) {
        if (ids == null) {
            return "";
        }
        return sourceRepository.findAllById(ids).stream().map(com.agenticpilot.source.DataSource::getName).sorted().collect(Collectors.joining(", "));
    }
}
