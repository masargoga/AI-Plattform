package com.agenticpilot.runtime;

import com.agenticpilot.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One message of a conversation. User text is stored after masking; messages that were stopped are stored as a placeholder. */
@Entity
@Table(name = "message")
public class StoredMessage extends AuditedEntity {

    public static final int MAX_LENGTH = 16000;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(nullable = false, updatable = false, length = 16)
    private String role;

    @Column(nullable = false, updatable = false, length = MAX_LENGTH)
    private String text;

    @Column(length = 4000, updatable = false)
    private String trace;

    protected StoredMessage() {
    }

    public StoredMessage(UUID conversationId, int ordinal, String role, String text, String trace) {
        this.conversationId = conversationId;
        this.ordinal = ordinal;
        this.role = role;
        this.text = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        this.trace = trace != null && trace.length() > 4000 ? null : trace;
    }

    public UUID getConversationId() { return conversationId; }
    public int getOrdinal() { return ordinal; }
    public String getRole() { return role; }
    public String getText() { return text; }
    public String getTrace() { return trace; }
}
