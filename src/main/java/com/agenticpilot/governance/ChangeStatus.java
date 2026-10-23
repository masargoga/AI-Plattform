package com.agenticpilot.governance;

/** State of one version of a skill or agent. DRAFT and PENDING are the open states; at most one version per item is open. */
public enum ChangeStatus {
    DRAFT, PENDING, APPROVED, REJECTED, WITHDRAWN;

    public boolean isOpen() {
        return this == DRAFT || this == PENDING;
    }
}
