package com.agenticpilot.run;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentKind;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.AgentVersionRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.GuardrailReader;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.runtime.BackgroundExecutor;
import com.agenticpilot.runtime.ModelGateway;
import com.agenticpilot.user.AppUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts a run for a signed-in user or an API key. It checks everything that can be checked before any work is done (agent
 * state, model connection, how many runs are going), saves the run and hands it to a background thread. Who may start a run
 * is decided by the caller.
 */
@Component
public class RunLauncher {

    /** Who starts a run. For an API key user is null and the actor is "api:" plus the key prefix. */
    public record Starter(AppUser user, UUID apiKeyId, String actor) {
    }

    public static final int INPUT_MAX = 1000;

    private final AgentRepository agents;
    private final AgentVersionRepository agentVersions;
    private final AgentRunRepository runs;
    private final GuardrailReader guardrails;
    private final ModelGateway gateway;
    private final RunProperties props;
    private final AuditService audit;
    private final BackgroundExecutor background;
    private final RunExecutor executor;
    private final TransactionTemplate tx;

    public RunLauncher(AgentRepository agents, AgentVersionRepository agentVersions, AgentRunRepository runs, GuardrailReader guardrails,
                       ModelGateway gateway, RunProperties props, AuditService audit, BackgroundExecutor background, RunExecutor executor,
                       PlatformTransactionManager txManager) {
        this.agents = agents;
        this.agentVersions = agentVersions;
        this.runs = runs;
        this.guardrails = guardrails;
        this.gateway = gateway;
        this.props = props;
        this.audit = audit;
        this.background = background;
        this.executor = executor;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Returns the id of the new run. It runs in the background; the raw input is passed in memory only. */
    public UUID start(UUID agentId, Starter starter, String rawInput, boolean draft) {
        String input = rawInput == null ? "" : rawInput.strip();
        if (input.isEmpty()) {
            throw new InvalidInputException("run.error.inputRequired");
        }
        if (input.length() > INPUT_MAX) {
            throw new InvalidInputException("error.tooLong", INPUT_MAX);
        }
        UUID runId = tx.execute(status -> create(agentId, starter, draft));
        background.submit(() -> executor.execute(runId, input));
        return runId;
    }

    private UUID create(UUID agentId, Starter starter, boolean draft) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        if (agent.getKind() != AgentKind.MANAGED) {
            throw new RunRefusedException("external");
        }
        if (!draft && !agent.isUsable()) {
            throw new RunRefusedException("not_approved");
        }
        if (agent.getStatus() == AgentStatus.PAUSED) {
            throw new RunRefusedException("paused");
        }
        AgentGuardrail g = guardrails.get(agentId);
        var refusal = gateway.refusalFor(g.getRegion());
        if (refusal.isPresent()) {
            throw new RunRefusedException(refusal.get() == ModelGateway.Refusal.EU_NOT_AVAILABLE ? "eu" : "no_model");
        }
        List<RunStatus> active = List.of(RunStatus.RUNNING, RunStatus.WAITING_FOR_APPROVAL);
        if (runs.countByAgentIdAndStatusIn(agentId, active) >= props.maxPerAgent()) {
            throw new RunRefusedException("busy", props.maxPerAgent());
        }
        if (runs.countByStatusIn(active) >= props.maxTotal()) {
            throw new RunRefusedException("busy_workspace");
        }
        int version = draft ? agentVersions.findByItemIdAndOpenFlag(agentId, 1).map(v -> v.getVersionNumber()).orElse(agent.getLiveVersion()) : agent.getLiveVersion();
        Instant now = Instant.now();
        Instant expires = g.getRetention() == Retention.UNLIMITED || g.getRetention() == Retention.NONE ? null : now.plus(Duration.ofDays(g.getRetention().days()));
        AgentRun run = runs.save(new AgentRun(agentId, version, starter.user(), starter.apiKeyId(), starter.actor(), draft,
                Math.min(agent.getMaxSteps(), AgentService.RunLimitsCeiling.STEPS), Math.min(agent.getMaxSeconds(), AgentService.RunLimitsCeiling.SECONDS),
                Math.min(agent.getMaxTokens(), AgentService.RunLimitsCeiling.TOKENS), now, expires));
        audit.recordAs(starter.actor(), agent.getId(), agent.getName(), "Run started" + (draft ? " (testing a draft)" : ""), AuditOutcome.ALLOWED, null,
                Map.of("runId", run.getId().toString(), "version", version));
        return run.getId();
    }
}
