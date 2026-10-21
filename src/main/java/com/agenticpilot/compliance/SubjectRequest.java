package com.agenticpilot.compliance;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A data subject request: someone asks to see or delete their data. */
@Entity
@Table(name = "subject_request")
public class SubjectRequest extends AuditedEntity {

    /** Less than this many days left counts as urgent. */
    public static final int URGENT_DAYS = 7;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubjectKind kind;

    @Column(nullable = false, length = 120)
    private String subject;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "done_at")
    private Instant doneAt;

    protected SubjectRequest() {
    }

    public SubjectRequest(SubjectKind kind, String subject, LocalDate dueDate) {
        this.kind = kind;
        this.subject = subject;
        this.dueDate = dueDate;
    }

    public boolean isDone() {
        return doneAt != null;
    }

    public boolean isUrgent(LocalDate today) {
        return !isDone() && today.plusDays(URGENT_DAYS).isAfter(dueDate);
    }

    public void complete(Instant when) {
        this.doneAt = when;
    }

    public SubjectKind getKind() { return kind; }
    public String getSubject() { return subject; }
    public LocalDate getDueDate() { return dueDate; }
    public Instant getDoneAt() { return doneAt; }
}
