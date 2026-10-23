package com.agenticpilot.support;

import com.agenticpilot.agent.AgentService;
import com.agenticpilot.skill.SkillService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Takes a new skill or agent through the approval the way the app does it: one Admin submits, the other approves.
 * The signed-in user of the test is restored afterwards.
 */
public final class Approve {

    private Approve() {
    }

    public static void skill(SkillService skills, UUID skillId) {
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        try {
            AsUser.admin();
            skills.submit(skillId, "Test setup");
            AsUser.admin2();
            skills.approve(skills.openVersion(skillId).orElseThrow().getId(), null);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(before);
        }
    }

    public static void agent(AgentService agents, UUID agentId) {
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        try {
            AsUser.admin();
            agents.submit(agentId, "Test setup");
            AsUser.admin2();
            agents.approve(agents.openVersion(agentId).orElseThrow().getId(), null);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(before);
        }
    }
}
