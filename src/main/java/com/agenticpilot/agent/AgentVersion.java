package com.agenticpilot.agent;

import com.agenticpilot.governance.ItemVersion;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "agent_version")
public class AgentVersion extends ItemVersion {

    protected AgentVersion() {
    }

    public AgentVersion(UUID agentId, int number, String snapshotJson, AppUser author) {
        super(agentId, number, snapshotJson, author);
    }
}
