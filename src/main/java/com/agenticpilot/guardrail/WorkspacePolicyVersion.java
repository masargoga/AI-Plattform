package com.agenticpilot.guardrail;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** One entry in the history of workspace policy changes. Never changed after it is written. */
@Entity
@Immutable
@Table(name = "workspace_policy_version")
public class WorkspacePolicyVersion extends AuditedEntity {

    @Column(nullable = false, updatable = false)
    private int revision;

    @Column(name = "personal_data_on", nullable = false, updatable = false)
    private boolean personalDataOn;

    @Column(name = "special_data_on", nullable = false, updatable = false)
    private boolean specialDataOn;

    @Column(name = "eu_only_on", nullable = false, updatable = false)
    private boolean euOnlyOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_retention", nullable = false, updatable = false, length = 16)
    private Retention defaultRetention;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "changed_by", updatable = false)
    private AppUser changedBy;

    protected WorkspacePolicyVersion() {
    }

    public WorkspacePolicyVersion(WorkspacePolicy policy) {
        this.revision = policy.getRevision();
        this.personalDataOn = policy.isPersonalDataOn();
        this.specialDataOn = policy.isSpecialDataOn();
        this.euOnlyOn = policy.isEuOnlyOn();
        this.defaultRetention = policy.getDefaultRetention();
        this.changedBy = policy.getChangedBy();
    }

    public int getRevision() { return revision; }
    public boolean isPersonalDataOn() { return personalDataOn; }
    public boolean isSpecialDataOn() { return specialDataOn; }
    public boolean isEuOnlyOn() { return euOnlyOn; }
    public Retention getDefaultRetention() { return defaultRetention; }
    public AppUser getChangedBy() { return changedBy; }
}
