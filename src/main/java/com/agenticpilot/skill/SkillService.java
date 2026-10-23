package com.agenticpilot.skill;

import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.governance.GovernedService;
import com.agenticpilot.governance.Lifecycle;
import com.agenticpilot.user.AppUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skills with an approval lifecycle (see {@link GovernedService}). Edits go into a draft; the skill's own fields are the
 * live, approved version. A new skill starts as a draft and cannot be used by an agent until it is approved.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class SkillService extends GovernedService<Skill, SkillVersion, SkillService.Content> {

    public static final int NAME_MAX = 48;

    /** The editable fields of a skill, also what a version stores. */
    public record Content(String name, SkillType type, String description, String triggerText, String instructions,
                          Set<Tool> tools, OutputFormat outputFormat, boolean complianceRelevant) {

        public Content(String name, SkillType type, String description, String triggerText, String instructions,
                       Set<Tool> tools, OutputFormat outputFormat) {
            this(name, type, description, triggerText, instructions, tools, outputFormat, false);
        }
    }

    private final SkillRepository skills;
    private final SkillVersionRepository skillVersions;
    private final AgentRepository agents;

    public SkillService(SkillRepository skills, SkillVersionRepository skillVersions, AgentRepository agents, CurrentUser currentUser,
                        AuditService audit) {
        super(skills, skillVersions, currentUser, audit, Content.class, "Skill");
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public List<Skill> search(SkillType type, String query) {
        return skills.search(type, query == null ? "" : query.strip().toLowerCase(Locale.ROOT));
    }

    /** All skills, in every state. */
    @Transactional(readOnly = true)
    public List<Skill> all() {
        return skills.search(null, "");
    }

    /** The skills an agent may be given: approved ones only. */
    @Transactional(readOnly = true)
    public List<Skill> approved() {
        return skills.search(null, "").stream().filter(Skill::isUsable).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public Skill get(UUID id) {
        return super.get(id);
    }

    @Transactional(readOnly = true)
    public boolean canEdit(Skill skill) {
        return currentUser.canEdit(skill.getCreatedBy());
    }

    /** Creates a skill as a draft (version 1). It is not usable until another Admin approves it. */
    public Skill create(Content content) {
        AppUser me = currentUser.require();
        Content valid = validate(content);
        Skill skill = new Skill(valid.name(), me);
        apply(skill, valid);
        skills.save(skill);
        skillVersions.save(newVersion(skill.getId(), 1, toJson(valid), me));
        audit.workspace("Skill \u201c" + skill.getName() + "\u201d created as a draft", AuditOutcome.ALLOWED, null, Map.of());
        return skill;
    }

    /** Stores the edit in the draft; the live version stays active until the draft is approved. */
    public Skill saveDraft(UUID id, Content content) {
        return saveDraftInternal(id, validate(content));
    }

    @Override
    protected Content snapshotOf(Skill s) {
        return contentOf(s);
    }

    public static Content contentOf(Skill s) {
        return new Content(s.getName(), s.getType(), s.getDescription(), s.getTriggerText(), s.getInstructions(),
                Set.copyOf(s.getTools()), s.getOutputFormat(), s.isComplianceRelevant());
    }

    @Override
    protected void apply(Skill skill, Content c) {
        skill.setName(c.name());
        skill.setType(c.type() == null ? SkillType.INSTRUCTION : c.type());
        skill.setDescription(c.description() == null ? "" : c.description());
        skill.setTriggerText(c.triggerText() == null ? "" : c.triggerText());
        skill.setInstructions(c.instructions() == null ? "" : c.instructions());
        skill.setTools(c.tools() == null ? Set.of() : c.tools());
        skill.setOutputFormat(c.outputFormat() == null ? OutputFormat.PLAIN_TEXT : c.outputFormat());
        skill.setComplianceRelevant(c.complianceRelevant());
    }

    @Override
    protected SkillVersion newVersion(UUID itemId, int number, String json, AppUser author) {
        return new SkillVersion(itemId, number, json, author);
    }

    @Override
    protected String nameOf(Content c) {
        return c.name();
    }

    @Override
    protected void checkUnused(Skill skill) {
        boolean used = agents.findBySkills_IdOrderByName(skill.getId()).stream().anyMatch(a -> a.getLifecycle() == Lifecycle.APPROVED);
        if (used) {
            throw new InvalidInputException("error.skill.inUse");
        }
    }

    @Override
    protected void beforeDelete(Skill skill) {
        agents.findBySkills_IdOrderByName(skill.getId()).forEach(a -> a.getSkills().remove(skill));
    }

    private static Content validate(Content c) {
        String name = c.name() == null ? "" : c.name().strip();
        if (name.isEmpty()) {
            throw new InvalidInputException("error.nameRequired");
        }
        if (name.length() > NAME_MAX) {
            throw new InvalidInputException("error.tooLong", NAME_MAX);
        }
        return new Content(name, c.type() == null ? SkillType.INSTRUCTION : c.type(), limit(c.description(), 500), limit(c.triggerText(), 1000),
                limit(c.instructions(), 8000), c.tools() == null ? Set.of() : Set.copyOf(c.tools()),
                c.outputFormat() == null ? OutputFormat.PLAIN_TEXT : c.outputFormat(), c.complianceRelevant());
    }

    private static String limit(String s, int max) {
        String v = s == null ? "" : s.strip();
        if (v.length() > max) {
            throw new InvalidInputException("error.tooLong", max);
        }
        return v;
    }
}
