package com.agenticpilot.guardrail;

import com.agenticpilot.agent.Agent;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** The built-in controls of one agent. The primary key is the agent's id. */
@Entity
@Table(name = "agent_guardrail")
public class AgentGuardrail {

    public static final int PURPOSE_MAX = 1000;

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "personal_data", nullable = false, length = 8)
    private PersonalDataMode personalData = PersonalDataMode.MASK;

    @Enumerated(EnumType.STRING)
    @Column(name = "special_data", nullable = false, length = 8)
    private SpecialDataMode specialData = SpecialDataMode.BLOCK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Region region = Region.EU;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Retention retention = Retention.DAYS_90;

    @Column(name = "hidden_instruction_protection", nullable = false)
    private boolean hiddenInstructionProtection = true;

    @Column(name = "human_review", nullable = false)
    private boolean humanReview = true;

    @Column(name = "max_records", nullable = false)
    private int maxRecords = 50;

    @Column(nullable = false, length = PURPOSE_MAX)
    private String purpose = "";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_approval_action", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "action", length = 16)
    @Enumerated(EnumType.STRING)
    private Set<ApprovalAction> approvals = EnumSet.noneOf(ApprovalAction.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AgentGuardrail() {
    }

    /** The recommended defaults for a new agent; retention comes from the workspace default. */
    public static AgentGuardrail defaultsFor(Agent agent, Retention defaultRetention) {
        AgentGuardrail g = new AgentGuardrail();
        g.agentId = agent.getId();
        g.retention = defaultRetention;
        g.purpose = agent.getJob() == null ? "" : agent.getJob();
        g.approvals = EnumSet.of(ApprovalAction.EMAIL);
        return g;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public GuardrailSettings toSettings() {
        return new GuardrailSettings(personalData, specialData, region, retention, hiddenInstructionProtection,
                Set.copyOf(approvals), purpose);
    }

    public UUID getAgentId() { return agentId; }
    public PersonalDataMode getPersonalData() { return personalData; }
    public void setPersonalData(PersonalDataMode v) { this.personalData = v; }
    public SpecialDataMode getSpecialData() { return specialData; }
    public void setSpecialData(SpecialDataMode v) { this.specialData = v; }
    public Region getRegion() { return region; }
    public void setRegion(Region v) { this.region = v; }
    public Retention getRetention() { return retention; }
    public void setRetention(Retention v) { this.retention = v; }
    public boolean isHiddenInstructionProtection() { return hiddenInstructionProtection; }
    public void setHiddenInstructionProtection(boolean v) { this.hiddenInstructionProtection = v; }
    public boolean isHumanReview() { return humanReview; }
    public void setHumanReview(boolean v) { this.humanReview = v; }
    public int getMaxRecords() { return maxRecords; }
    public void setMaxRecords(int v) { this.maxRecords = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v == null ? "" : v.strip(); }
    public Set<ApprovalAction> getApprovals() { return approvals; }
    public void setApprovals(Set<ApprovalAction> v) { this.approvals = v.isEmpty() ? EnumSet.noneOf(ApprovalAction.class) : EnumSet.copyOf(v); }
}
