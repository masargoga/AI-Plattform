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
import java.util.UUID;

/** The single live workspace policy. Every change also gets a row in {@link WorkspacePolicyVersion}. */
@Entity
@Table(name = "workspace_policy")
public class WorkspacePolicy extends AuditedEntity {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Column(name = "personal_data_on", nullable = false)
    private boolean personalDataOn;

    @Column(name = "special_data_on", nullable = false)
    private boolean specialDataOn;

    @Column(name = "eu_only_on", nullable = false)
    private boolean euOnlyOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_retention", nullable = false, length = 16)
    private Retention defaultRetention = Retention.DAYS_90;

    @Column(nullable = false)
    private int revision = 1;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "changed_by")
    private AppUser changedBy;

    protected WorkspacePolicy() {
    }

    public boolean isPersonalDataOn() { return personalDataOn; }
    public boolean isSpecialDataOn() { return specialDataOn; }
    public boolean isEuOnlyOn() { return euOnlyOn; }
    public Retention getDefaultRetention() { return defaultRetention; }
    public int getRevision() { return revision; }
    public AppUser getChangedBy() { return changedBy; }

    public void apply(boolean personalDataOn, boolean specialDataOn, boolean euOnlyOn, Retention defaultRetention, AppUser by) {
        this.personalDataOn = personalDataOn;
        this.specialDataOn = specialDataOn;
        this.euOnlyOn = euOnlyOn;
        this.defaultRetention = defaultRetention;
        this.changedBy = by;
        this.revision++;
    }
}
