package com.agenticpilot.source;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A passage of about 800 characters of a document, the unit that retrieval returns. */
@Entity
@Table(name = "chunk")
public class Chunk extends AuditedEntity {

    public static final int MAX_LENGTH = 2000;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(nullable = false, updatable = false, length = MAX_LENGTH)
    private String text;

    protected Chunk() {
    }

    public Chunk(UUID documentId, int ordinal, String text) {
        this.documentId = documentId;
        this.ordinal = ordinal;
        this.text = text;
    }

    public UUID getDocumentId() { return documentId; }
    public int getOrdinal() { return ordinal; }
    public String getText() { return text; }
}
