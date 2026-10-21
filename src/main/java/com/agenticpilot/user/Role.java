package com.agenticpilot.user;

public enum Role {
    ADMIN,
    AGENT_OWNER;

    public String authority() {
        return "ROLE_" + name();
    }
}
