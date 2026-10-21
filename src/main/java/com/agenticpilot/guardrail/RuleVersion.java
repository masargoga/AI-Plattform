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
import java.time.Instant;
import java.util.UUID;

/**
 * One proposed or decided version of a rule. The content (snapshot, author, note) is written once. The only later
 * change is the single decision: PENDING becomes APPROVED, REJECTED or WITHDRAWN. The approver always differs from the
 * author, which the database also checks.
 */
@Entity
@Table(name = "rule_version")
public class RuleVersion extends AuditedEntity {

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false, updatable = false, length = 4000)
    private String snapshot;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "author_id", updatable = false)
    private AppUser author;

    @Column(nullable = false, updatable = false, length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionStatus status = VersionStatus.PENDING;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "approver_id")
    private AppUser approver;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** 1 while pending, null afterwards: a unique constraint keeps one pending version per rule. */
    @Column(name = "pending_flag")
    private Integer pendingFlag = 1;

    protected RuleVersion() {
    }

    public RuleVersion(UUID ruleId, int versionNumber, String snapshotJson, AppUser author, String note) {
        this.ruleId = ruleId;
        this.versionNumber = versionNumber;
        this.snapshot = snapshotJson;
        this.author = author;
        this.note = note;
    }

    /** Records the one decision. Only a pending version can be decided. */
    public void decide(VersionStatus decision, AppUser by, Instant when) {
        if (status != VersionStatus.PENDING || decision == VersionStatus.PENDING) {
            throw new IllegalStateException("Version already decided");
        }
        this.status = decision;
        this.approver = decision == VersionStatus.WITHDRAWN ? null : by;
        this.decidedAt = when;
        this.pendingFlag = null;
    }

    public UUID getRuleId() { return ruleId; }
    public int getVersionNumber() { return versionNumber; }
    public String getSnapshotJson() { return snapshot; }
    public AppUser getAuthor() { return author; }
    public String getNote() { return note; }
    public VersionStatus getStatus() { return status; }
    public AppUser getApprover() { return approver; }
    public Instant getDecidedAt() { return decidedAt; }
}
