package com.agenticpilot.skill;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The outcome of running a test case against a version of the skill (live or draft). */
@Entity
@Table(name = "skill_test_result")
public class SkillTestResult extends AuditedEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false, updatable = false)
    private boolean draft;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TestOutcome outcome;

    @Column(nullable = false, length = 2000, updatable = false)
    private String detail;

    @Column(name = "run_at", nullable = false, updatable = false)
    private Instant runAt;

    protected SkillTestResult() {
    }

    public SkillTestResult(UUID caseId, UUID skillId, int versionNumber, boolean draft, TestOutcome outcome, String detail, Instant runAt) {
        this.caseId = caseId;
        this.skillId = skillId;
        this.versionNumber = versionNumber;
        this.draft = draft;
        this.outcome = outcome;
        this.detail = detail == null ? "" : detail.length() > 2000 ? detail.substring(0, 2000) : detail;
        this.runAt = runAt;
    }

    public UUID getCaseId() { return caseId; }
    public UUID getSkillId() { return skillId; }
    public int getVersionNumber() { return versionNumber; }
    public boolean isDraft() { return draft; }
    public TestOutcome getOutcome() { return outcome; }
    public String getDetail() { return detail; }
    public Instant getRunAt() { return runAt; }
}
