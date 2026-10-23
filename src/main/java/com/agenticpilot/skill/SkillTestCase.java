package com.agenticpilot.skill;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A sample input for a skill with up to three checks on the answer. Not versioned; belongs to the skill. */
@Entity
@Table(name = "skill_test_case")
public class SkillTestCase extends AuditedEntity {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 4000)
    private String input;

    @Column(nullable = false, length = 4000)
    private String checks;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    protected SkillTestCase() {
    }

    public SkillTestCase(UUID skillId, String name, String input, List<SkillCheck> checks, AppUser createdBy) {
        this.skillId = skillId;
        this.createdBy = createdBy;
        update(name, input, checks);
    }

    public void update(String name, String input, List<SkillCheck> checks) {
        this.name = name;
        this.input = input;
        this.checks = JSON.writeValueAsString(checks);
    }

    public List<SkillCheck> getChecks() {
        return JSON.readValue(checks, new TypeReference<List<SkillCheck>>() { });
    }

    public UUID getSkillId() { return skillId; }
    public String getName() { return name; }
    public String getInput() { return input; }
    public AppUser getCreatedBy() { return createdBy; }
}
