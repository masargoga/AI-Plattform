package com.agenticpilot.governance;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentVersion;
import com.agenticpilot.agent.AgentVersionRepository;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillVersion;
import com.agenticpilot.skill.SkillVersionRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives every approved skill and agent that has no version record yet an approved version, with its current content as the
 * snapshot. This is how content from before the approval lifecycle (migration V7) and demo data become version 1.
 * Runs at every start and does nothing when all versions exist.
 */
@Component
@Order(30)
class ApprovedBaseline implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovedBaseline.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SkillRepository skills;
    private final SkillVersionRepository skillVersions;
    private final AgentRepository agents;
    private final AgentVersionRepository agentVersions;

    ApprovedBaseline(SkillRepository skills, SkillVersionRepository skillVersions, AgentRepository agents, AgentVersionRepository agentVersions) {
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.agents = agents;
        this.agentVersions = agentVersions;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int count = 0;
        for (Skill s : skills.findAll()) {
            if (s.getLiveVersion() > 0 && skillVersions.findTopByItemIdOrderByVersionNumberDesc(s.getId()).isEmpty()) {
                SkillVersion v = new SkillVersion(s.getId(), s.getLiveVersion(), JSON.writeValueAsString(SkillService.contentOf(s)), s.getCreatedBy());
                v.approveAtCreation(s.getCreatedBy(), Instant.now(), "Migrated");
                skillVersions.save(v);
                count++;
            }
        }
        for (Agent a : agents.findAll()) {
            if (a.getLiveVersion() > 0 && agentVersions.findTopByItemIdOrderByVersionNumberDesc(a.getId()).isEmpty()) {
                AgentVersion v = new AgentVersion(a.getId(), a.getLiveVersion(), JSON.writeValueAsString(AgentService.contentOf(a)), a.getOwner());
                v.approveAtCreation(a.getOwner(), Instant.now(), "Migrated");
                agentVersions.save(v);
                count++;
            }
        }
        if (count > 0) {
            log.info("Created {} approved baseline versions", count);
        }
    }
}
