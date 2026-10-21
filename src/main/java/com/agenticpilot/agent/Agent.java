package com.agenticpilot.agent;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "agent")
public class Agent extends AuditedEntity {

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 200)
    private String job = "";

    @Column(nullable = false, length = 8000)
    private String instructions = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentKind kind = AgentKind.MANAGED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentStatus status = AgentStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Autonomy autonomy = Autonomy.ASK;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_tier", nullable = false, length = 16)
    private ModelTier modelTier = ModelTier.BALANCED;

    @Column(name = "last_run")
    private Instant lastRun;

    @Column(name = "run_count", nullable = false)
    private int runCount;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "agent_skill", joinColumns = @JoinColumn(name = "agent_id"), inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> skills = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "agent_source", joinColumns = @JoinColumn(name = "agent_id"), inverseJoinColumns = @JoinColumn(name = "source_id"))
    private Set<DataSource> sources = new LinkedHashSet<>();

    protected Agent() {
    }

    public Agent(String name, AppUser owner) {
        this(name, owner, AgentKind.MANAGED);
    }

    public Agent(String name, AppUser owner, AgentKind kind) {
        this.name = name;
        this.owner = owner;
        this.kind = kind;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getJob() { return job; }
    public void setJob(String job) { this.job = job; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public AgentKind getKind() { return kind; }
    public AgentStatus getStatus() { return status; }
    public void setStatus(AgentStatus status) { this.status = status; }
    public Autonomy getAutonomy() { return autonomy; }
    public void setAutonomy(Autonomy autonomy) { this.autonomy = autonomy; }
    public ModelTier getModelTier() { return modelTier; }
    public void setModelTier(ModelTier modelTier) { this.modelTier = modelTier; }
    public Instant getLastRun() { return lastRun; }
    public int getRunCount() { return runCount; }
    public AppUser getOwner() { return owner; }
    public Set<Skill> getSkills() { return skills; }
    public Set<DataSource> getSources() { return sources; }

    public void recordRun(Instant when, int runs) {
        this.lastRun = when;
        this.runCount = runs;
    }
}
