package com.agenticpilot.skill;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "skill")
public class Skill extends AuditedEntity {

    @Column(nullable = false, length = 48)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SkillType type = SkillType.INSTRUCTION;

    @Column(nullable = false, length = 500)
    private String description = "";

    @Column(name = "trigger_text", nullable = false, length = 1000)
    private String triggerText = "";

    @Column(nullable = false, length = 8000)
    private String instructions = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 16)
    private OutputFormat outputFormat = OutputFormat.PLAIN_TEXT;

    /** Shown as v1, v2... Raised on every save. */
    @Column(nullable = false)
    private int revision = 1;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "skill_tool", joinColumns = @JoinColumn(name = "skill_id"))
    @Column(name = "tool", length = 32)
    @Enumerated(EnumType.STRING)
    private Set<Tool> tools = EnumSet.noneOf(Tool.class);

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    protected Skill() {
    }

    public Skill(String name, AppUser createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public SkillType getType() { return type; }
    public void setType(SkillType type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTriggerText() { return triggerText; }
    public void setTriggerText(String triggerText) { this.triggerText = triggerText; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public void setOutputFormat(OutputFormat outputFormat) { this.outputFormat = outputFormat; }
    public int getRevision() { return revision; }
    public void raiseRevision() { revision++; }
    public Set<Tool> getTools() { return tools; }
    public void setTools(Set<Tool> tools) { this.tools = tools.isEmpty() ? EnumSet.noneOf(Tool.class) : EnumSet.copyOf(tools); }
    public AppUser getCreatedBy() { return createdBy; }
}
