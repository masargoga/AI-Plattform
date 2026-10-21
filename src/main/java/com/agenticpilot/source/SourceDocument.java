package com.agenticpilot.source;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A document of a data source. Only the extracted text is kept (as chunks); the uploaded original is not stored. */
@Entity
@Table(name = "source_document")
public class SourceDocument extends AuditedEntity {

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 500)
    private String uri;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    protected SourceDocument() {
    }

    public SourceDocument(UUID sourceId, String title, String uri, String contentHash) {
        this.sourceId = sourceId;
        this.title = title.length() > 255 ? title.substring(0, 255) : title;
        this.uri = uri;
        this.contentHash = contentHash;
    }

    public UUID getSourceId() { return sourceId; }
    public String getTitle() { return title; }
    public String getUri() { return uri; }
    public String getContentHash() { return contentHash; }
}
