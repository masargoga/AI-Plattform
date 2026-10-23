package com.agenticpilot.agent;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.governance.Governed;
import com.agenticpilot.governance.Lifecycle;
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
public class Agent extends AuditedEntity implements Governed {

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

    /** The number of the live (approved) version. Zero until the first version is approved. */
    @Column(name = "live_version", nullable = false)
    private int liveVersion = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 16)
    private Lifecycle lifecycle = Lifecycle.DRAFT;

    /** A checkable statement of what the agent is responsible for. A run lasts until it is met. */
    @Column(nullable = false, length = 1000)
    private String goal = "";

    /** The label of the one input a run takes, for example "Customer id". */
    @Column(name = "input_label", nullable = false, length = 40)
    private String inputLabel = "Task";

    @Column(name = "max_steps", nullable = false)
    private int maxSteps = 12;

    @Column(name = "max_seconds", nullable = false)
    private int maxSeconds = 300;

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens = 100_000;

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
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getInputLabel() { return inputLabel; }
    public void setInputLabel(String inputLabel) { this.inputLabel = inputLabel; }
    public int getMaxSteps() { return maxSteps; }
    public int getMaxSeconds() { return maxSeconds; }
    public int getMaxTokens() { return maxTokens; }

    public void setLimits(int maxSteps, int maxSeconds, int maxTokens) {
        this.maxSteps = maxSteps;
        this.maxSeconds = maxSeconds;
        this.maxTokens = maxTokens;
    }

    @Override public int getLiveVersion() { return liveVersion; }
    @Override public void setLiveVersion(int liveVersion) { this.liveVersion = liveVersion; }
    @Override public Lifecycle getLifecycle() { return lifecycle; }
    @Override public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    @Override public AppUser getResponsible() { return owner; }

    public void recordRun(Instant when, int runs) {
        this.lastRun = when;
        this.runCount = runs;
    }
}
