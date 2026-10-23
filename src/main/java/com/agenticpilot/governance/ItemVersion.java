package com.agenticpilot.governance;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of a skill or agent. A draft can still be edited. A submitted (pending) version cannot; it gets exactly one
 * decision (approved, rejected or withdrawn), after which it never changes. The approver differs from the author, which the
 * database also checks.
 */
@MappedSuperclass
public abstract class ItemVersion extends AuditedEntity {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false, length = 30000)
    private String snapshot;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "author_id")
    private AppUser author;

    @Column(nullable = false, length = 500)
    private String note = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChangeStatus status = ChangeStatus.DRAFT;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "approver_id")
    private AppUser approver;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment", length = 500)
    private String decisionComment;

    /** 1 while draft or pending, null afterwards: a unique constraint keeps one open version per item. */
    @Column(name = "open_flag")
    private Integer openFlag = 1;

    protected ItemVersion() {
    }

    protected ItemVersion(UUID itemId, int versionNumber, String snapshotJson, AppUser author) {
        this.itemId = itemId;
        this.versionNumber = versionNumber;
        this.snapshot = snapshotJson;
        this.author = author;
    }

    /** Replaces the content of a draft. */
    public void edit(String snapshotJson, AppUser editor) {
        requireStatus(ChangeStatus.DRAFT);
        this.snapshot = snapshotJson;
        this.author = editor;
    }

    /** Hands the draft to a reviewer. The submitter is the author for the four-eyes rule. */
    public void submit(AppUser submitter, String changeNote) {
        requireStatus(ChangeStatus.DRAFT);
        this.author = submitter;
        this.note = changeNote;
        this.status = ChangeStatus.PENDING;
    }

    /** Records the one decision. Only a pending version can be decided. */
    public void decide(ChangeStatus decision, AppUser by, String comment, Instant when) {
        requireStatus(ChangeStatus.PENDING);
        if (decision == ChangeStatus.DRAFT || decision == ChangeStatus.PENDING) {
            throw new IllegalArgumentException("Not a decision: " + decision);
        }
        this.status = decision;
        this.approver = decision == ChangeStatus.WITHDRAWN ? null : by;
        this.decisionComment = comment;
        this.decidedAt = when;
        this.openFlag = null;
    }

    /** A draft that is discarded or a version that is approved at creation (migration, seed data). */
    public void approveAtCreation(AppUser by, Instant when, String changeNote) {
        requireStatus(ChangeStatus.DRAFT);
        this.note = changeNote;
        this.status = ChangeStatus.APPROVED;
        this.decidedAt = when;
        this.openFlag = null;
    }

    private void requireStatus(ChangeStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Version is " + status + ", expected " + expected);
        }
    }

    public UUID getItemId() { return itemId; }
    public int getVersionNumber() { return versionNumber; }
    public String getSnapshotJson() { return snapshot; }
    public AppUser getAuthor() { return author; }
    public String getNote() { return note; }
    public ChangeStatus getStatus() { return status; }
    public AppUser getApprover() { return approver; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionComment() { return decisionComment; }
}
