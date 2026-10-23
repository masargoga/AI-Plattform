package com.agenticpilot.run;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.user.AppUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs for signed-in users: start, stop, look at. Starting needs the agent's owner or an Admin. */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class RunService {

    private final RunLauncher launcher;
    private final AgentRunRepository runs;
    private final AgentRunStepRepository steps;
    private final AgentRepository agents;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public RunService(RunLauncher launcher, AgentRunRepository runs, AgentRunStepRepository steps, AgentRepository agents, CurrentUser currentUser,
                      AuditService audit, JdbcTemplate jdbc) {
        this.launcher = launcher;
        this.runs = runs;
        this.steps = steps;
        this.agents = agents;
        this.currentUser = currentUser;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** Starts the agent on an input. With draft = true the draft of the agent and its skills is used. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public UUID start(UUID agentId, String input, boolean draft) {
        AppUser me = currentUser.require();
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        currentUser.requireEdit(agent.getOwner());
        return launcher.start(agentId, new RunLauncher.Starter(me, null, me.getUsername()), input, draft);
    }

    public void stop(UUID runId) {
        AgentRun run = get(runId);
        if (run.isFinished()) {
            return;
        }
        jdbc.update("update agent_run set cancel_requested = true, version = version + 1 where id = ? and status <> 'FINISHED'", runId);
        Agent agent = agents.findById(run.getAgentId()).orElse(null);
        audit.workspace("Run stop requested by " + currentUser.require().getDisplayName(), AuditOutcome.ALLOWED, null,
                Map.of("runId", runId.toString(), "agent", agent == null ? "" : agent.getName()));
    }

    @Transactional(readOnly = true)
    public AgentRun get(UUID runId) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Run"));
        requireAccess(run);
        return run;
    }

    @Transactional(readOnly = true)
    public List<AgentRunStep> steps(UUID runId) {
        get(runId);
        return steps.findByRunIdOrderByOrdinal(runId);
    }

    /** The newest runs of an agent, for its owner and for Admins. */
    @Transactional(readOnly = true)
    public List<AgentRun> forAgent(UUID agentId, int limit) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        currentUser.requireEdit(agent.getOwner());
        return runs.findByAgentIdOrderByStartedAtDesc(agentId, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
    }

    private void requireAccess(AgentRun run) {
        AppUser me = currentUser.require();
        if (me.isAdmin() || (run.getStartedBy() != null && run.getStartedBy().getId().equals(me.getId()))) {
            return;
        }
        Agent agent = agents.findById(run.getAgentId()).orElseThrow(() -> new NotFoundException("Agent"));
        if (!agent.getOwner().getId().equals(me.getId())) {
            throw new AccessDeniedException("Not your run");
        }
    }
}
