package com.agenticpilot.governance;

import com.agenticpilot.agent.AgentService;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.source.TrustLevel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warnings (never blocks) about an agent configuration. A skill marked compliance relevant should rest on authoritative
 * data: when every source of the agent is only informational, or there is none, the reviewer is told. Returns message keys.
 */
@Component
public class GovernanceWarnings {

    public static final String INFORMATIONAL_ONLY = "warn.compliance.informationalOnly";
    public static final String NO_SOURCES = "warn.compliance.noSources";

    private final SkillRepository skills;
    private final DataSourceRepository sources;

    public GovernanceWarnings(SkillRepository skills, DataSourceRepository sources) {
        this.skills = skills;
        this.sources = sources;
    }

    @Transactional(readOnly = true)
    public List<String> forAgent(AgentService.Content config) {
        List<String> out = new ArrayList<>();
        if (config.skillIds() == null || config.skillIds().isEmpty()) {
            return out;
        }
        boolean relevant = skills.findAllById(config.skillIds()).stream().anyMatch(Skill::isComplianceRelevant);
        if (!relevant) {
            return out;
        }
        List<DataSource> attached = config.sourceIds() == null ? List.of() : sources.findAllById(config.sourceIds());
        if (attached.isEmpty()) {
            out.add(NO_SOURCES);
        } else if (attached.stream().noneMatch(s -> s.getTrustLevel() == TrustLevel.AUTHORITATIVE)) {
            out.add(INFORMATIONAL_ONLY);
        }
        return out;
    }
}
