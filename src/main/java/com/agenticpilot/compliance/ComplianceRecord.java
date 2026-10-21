package com.agenticpilot.compliance;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "compliance_record")
public class ComplianceRecord extends AuditedEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "owner_name", nullable = false, length = 80)
    private String ownerName;

    /** Free text next to the date, such as "signed" or "not started". */
    @Column(nullable = false, length = 200)
    private String note = "";

    /** Due date while open, or the date it was last updated or signed. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecordStatus status = RecordStatus.TODO;

    protected ComplianceRecord() {
    }

    public ComplianceRecord(String title, String ownerName, String note, LocalDate dueDate, RecordStatus status) {
        this.title = title;
        this.ownerName = ownerName;
        this.note = note;
        this.dueDate = dueDate;
        this.status = status;
    }

    public void markDone(LocalDate today) {
        this.status = RecordStatus.DONE;
        this.dueDate = today;
        this.note = "completed";
    }

    public String getTitle() { return title; }
    public String getOwnerName() { return ownerName; }
    public String getNote() { return note; }
    public LocalDate getDueDate() { return dueDate; }
    public RecordStatus getStatus() { return status; }
}
