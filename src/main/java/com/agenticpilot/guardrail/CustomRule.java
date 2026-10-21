package com.agenticpilot.guardrail;

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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A custom rule. Its live fields always equal its newest approved version; liveVersion 0 means it was never approved
 * and is not live.
 */
@Entity
@Table(name = "custom_rule")
public class CustomRule extends AuditedEntity {

    @Column(nullable = false, length = 48)
    private String name;

    @Column(nullable = false, length = 500)
    private String catches;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RuleAction action;

    @Column(nullable = false, length = 500)
    private String message = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RuleScope scope;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "live_version", nullable = false)
    private int liveVersion;

    @Column(nullable = false, length = 500)
    private String sample = "";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "custom_rule_agent", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "agent_id")
    private Set<UUID> agentIds = new LinkedHashSet<>();

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    protected CustomRule() {
    }

    public CustomRule(RuleSnapshot first, AppUser createdBy) {
        this.createdBy = createdBy;
        applySnapshot(first);
    }

    /** Copies the fields of a version onto the rule (used when a version is approved, and for the initial draft). */
    public void applySnapshot(RuleSnapshot s) {
        this.name = s.name();
        this.catches = s.catches();
        this.action = s.action();
        this.message = s.message() == null ? "" : s.message();
        this.scope = s.scope();
        this.sample = s.sample() == null ? "" : s.sample();
        this.agentIds.clear();
        if (s.scope() == RuleScope.SELECTED) {
            this.agentIds.addAll(s.agentIds());
        }
    }

    public RuleSnapshot toSnapshot() {
        return new RuleSnapshot(name, catches, action, message, scope, new LinkedHashSet<>(agentIds), sample);
    }

    public boolean isLive() {
        return enabled && liveVersion > 0;
    }

    public boolean appliesTo(UUID agentId) {
        return isLive() && (scope == RuleScope.ALL || agentIds.contains(agentId));
    }

    public String getName() { return name; }
    public String getCatches() { return catches; }
    public RuleAction getAction() { return action; }
    public String getMessage() { return message; }
    public RuleScope getScope() { return scope; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getLiveVersion() { return liveVersion; }
    public void setLiveVersion(int liveVersion) { this.liveVersion = liveVersion; }
    public String getSample() { return sample; }
    public Set<UUID> getAgentIds() { return agentIds; }
    public AppUser getCreatedBy() { return createdBy; }
}
