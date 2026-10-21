package com.agenticpilot.skill;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class SkillService {

    public static final int NAME_MAX = 48;

    /** The editable fields of a skill. */
    public record Content(String name, SkillType type, String description, String triggerText, String instructions,
                          Set<Tool> tools, OutputFormat outputFormat) {
    }

    private final SkillRepository skills;
    private final CurrentUser currentUser;

    private final AuditService audit;

    public SkillService(SkillRepository skills, CurrentUser currentUser, AuditService audit) {
        this.skills = skills;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Skill> search(SkillType type, String query) {
        return skills.search(type, query == null ? "" : query.strip().toLowerCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public List<Skill> all() {
        return skills.search(null, "");
    }

    @Transactional(readOnly = true)
    public Skill get(UUID id) {
        return skills.findById(id).orElseThrow(() -> new NotFoundException("Skill"));
    }

    @Transactional(readOnly = true)
    public boolean canEdit(Skill skill) {
        return currentUser.canEdit(skill.getCreatedBy());
    }

    public Skill create(Content content) {
        Skill skill = new Skill(validName(content.name()), currentUser.require());
        apply(skill, content);
        skills.save(skill);
        audit.workspace("Skill \u201c" + skill.getName() + "\u201d created", AuditOutcome.ALLOWED, null, Map.of());
        return skill;
    }

    /** Saving raises the version number shown as v1, v2, ... */
    public Skill update(UUID id, Content content) {
        Skill skill = get(id);
        currentUser.requireEdit(skill.getCreatedBy());
        skill.setName(validName(content.name()));
        apply(skill, content);
        skill.raiseRevision();
        audit.workspace("Skill \u201c" + skill.getName() + "\u201d saved as v" + skill.getRevision(), AuditOutcome.ALLOWED, null, Map.of());
        return skill;
    }

    private static void apply(Skill skill, Content c) {
        skill.setType(c.type() == null ? SkillType.INSTRUCTION : c.type());
        skill.setDescription(limit(c.description(), 500));
        skill.setTriggerText(limit(c.triggerText(), 1000));
        skill.setInstructions(limit(c.instructions(), 8000));
        skill.setTools(c.tools() == null ? Set.of() : c.tools());
        skill.setOutputFormat(c.outputFormat() == null ? OutputFormat.PLAIN_TEXT : c.outputFormat());
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

    private static String limit(String s, int max) {
        String v = s == null ? "" : s.strip();
        if (v.length() > max) {
            throw new InvalidInputException("error.tooLong", max);
        }
        return v;
    }
}
