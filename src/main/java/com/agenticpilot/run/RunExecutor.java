package com.agenticpilot.run;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentVersionRepository;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.governance.ItemVersion;
import com.agenticpilot.guardrail.AgentGuardContext;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.guardrail.GuardrailEngine;
import com.agenticpilot.guardrail.GuardrailReader;
import com.agenticpilot.guardrail.InputResult;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleSpec;
import com.agenticpilot.guardrail.SpecialDataMode;
import com.agenticpilot.run.RunTools.SkillRef;
import com.agenticpilot.run.RunTools.SourceRef;
import com.agenticpilot.runtime.GuardrailContextFactory;
import com.agenticpilot.runtime.GuardrailRecorder;
import com.agenticpilot.runtime.ModelGateway;
import com.agenticpilot.runtime.PendingAction;
import com.agenticpilot.runtime.PendingActionRepository;
import com.agenticpilot.runtime.PendingActionStatus;
import com.agenticpilot.runtime.provider.ChatMessage;
import com.agenticpilot.runtime.provider.ModelException;
import com.agenticpilot.runtime.provider.ModelResponse;
import com.agenticpilot.runtime.provider.ToolCall;
import com.agenticpilot.runtime.provider.ToolResult;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillVersionRepository;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.user.AppUser;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent loop. The model only reads and writes text; this class is the program around it. It builds the prompt, calls the
 * model, reads the answer, checks every request the model makes against the agent's permissions and limits, does the work
 * itself, puts the result back into the prompt and repeats, until the model calls finish or a limit stops the run.
 *
 * <p>Everything that goes to the model passes the guardrails (input, data from sources), everything that is shown or stored is
 * masked, and every step is saved as it happens so another replica can show the run. The audit log gets decisions and counts,
 * never the input, the data or the result.
 */
@Component
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentRunRepository runs;
    private final AgentRunStepRepository steps;
    private final AgentRepository agents;
    private final GuardrailReader guardrails;
    private final GuardrailContextFactory contexts;
    private final ModelGateway gateway;
    private final RunTools tools;
    private final GuardrailRecorder recorder;
    private final AuditService audit;
    private final PendingActionRepository pendingActions;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersions;
    private final AgentVersionRepository agentVersions;
    private final DataSourceRepository sourceRepository;
    private final JdbcTemplate jdbc;
    private final RunProperties props;
    private final TransactionTemplate tx;

    public RunExecutor(AgentRunRepository runs, AgentRunStepRepository steps, AgentRepository agents, GuardrailReader guardrails,
                       GuardrailContextFactory contexts, ModelGateway gateway, RunTools tools, GuardrailRecorder recorder, AuditService audit,
                       PendingActionRepository pendingActions, SkillRepository skillRepository, SkillVersionRepository skillVersions,
                       AgentVersionRepository agentVersions, DataSourceRepository sourceRepository, JdbcTemplate jdbc, RunProperties props,
                       PlatformTransactionManager txManager) {
        this.runs = runs;
        this.steps = steps;
        this.agents = agents;
        this.guardrails = guardrails;
        this.contexts = contexts;
        this.gateway = gateway;
        this.tools = tools;
        this.recorder = recorder;
        this.audit = audit;
        this.pendingActions = pendingActions;
        this.skillRepository = skillRepository;
        this.skillVersions = skillVersions;
        this.agentVersions = agentVersions;
        this.sourceRepository = sourceRepository;
        this.jdbc = jdbc;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** What a run needs, read once at the start. */
    private record Setup(UUID runId, UUID agentId, String agentName, String actor, AppUser startedBy, boolean draft, ModelTier tier, Region region,
                         AgentGuardContext guard, boolean hiddenProtection, boolean storeNothing, String system, String inputLabel,
                         RunTools.Context toolContext, int maxSteps, int maxTokens, Instant deadline) {
    }

    /** Ends the run with a reason. Thrown inside the loop and caught at the top. */
    private static final class StopRun extends RuntimeException {
        final StopReason reason;
        final String result;
        final String detail;

        StopRun(StopReason reason, String result, String detail) {
            super(reason.name(), null, false, false);
            this.reason = reason;
            this.result = result;
            this.detail = detail;
        }
    }

    /** The mutable state of one run. */
    private static final class State {
        int ordinal;
        int modelTurns;
        int tokensIn;
        int tokensOut;
        int refused;
        int approvals;
        List<String> notes = new ArrayList<>();
    }

    /**
     * Runs the loop to its end. Called on a worker thread. The raw input only lives in memory: the database gets the masked
     * input once it has passed the guardrails.
     */
    public void execute(UUID runId, String rawInput) {
        Setup setup;
        try {
            setup = tx.execute(status -> prepare(runId));
        } catch (RuntimeException e) {
            log.warn("Run {} could not be prepared: {}", runId, e.getClass().getSimpleName());
            finish(runId, StopReason.FAILED, null, "The run could not be prepared.", false, null);
            return;
        }
        State st = new State();
        try {
            loop(setup, st, rawInput);
        } catch (StopRun stop) {
            conclude(setup, st, stop.reason, stop.result, stop.detail);
        } catch (ModelException e) {
            log.warn("Model call failed in run {}: {}", runId, e.getMessage());
            conclude(setup, st, StopReason.FAILED, null, "The model could not answer.");
        } catch (RuntimeException e) {
            log.warn("Run {} failed: {}", runId, e.getClass().getSimpleName());
            conclude(setup, st, StopReason.FAILED, null, "The run failed unexpectedly.");
        }
    }

    // ----- setup -----

    private Setup prepare(UUID runId) {
        AgentRun run = runs.findById(runId).orElseThrow();
        Agent agent = agents.findById(run.getAgentId()).orElseThrow();
        AgentService.Content config = run.isDraft() ? draftConfig(agent) : AgentService.contentOf(agent);
        AgentGuardrail g = guardrails.get(agent.getId());
        AgentGuardContext guard = contexts.build(g);

        List<Skill> skillList = run.isDraft() ? skillRepository.findAllById(config.skillIds()).stream().filter(s -> s.getLifecycle() != com.agenticpilot.governance.Lifecycle.RETIRED).toList()
                : agent.getSkills().stream().filter(Skill::isUsable).toList();
        List<SkillRef> skills = skillList.stream().sorted(Comparator.comparing(Skill::getName)).map(s -> skillRef(s, run.isDraft())).toList();
        List<DataSource> sourceList = run.isDraft() ? sourceRepository.findAllById(config.sourceIds()) : List.copyOf(agent.getSources());
        List<SourceRef> sources = sourceList.stream().sorted(Comparator.comparing(DataSource::getName))
                .map(d -> new SourceRef(d.getId(), d.getName(), d.getKind(), d.getItemCount())).toList();

        String goal = config.goal() == null ? "" : config.goal();
        String system = RunPrompt.system(config.name(), goal, config.job(), config.instructions(), g.getPurpose(), skills, sources);
        Instant deadline = run.getStartedAt().plusSeconds(run.getMaxSeconds());
        return new Setup(runId, agent.getId(), config.name(), run.getActor(), run.getStartedBy(), run.isDraft(),
                config.modelTier() == null ? ModelTier.BALANCED : config.modelTier(), g.getRegion(), guard, g.isHiddenInstructionProtection(),
                g.getRetention() == Retention.NONE, system, config.inputLabel() == null ? "Task" : config.inputLabel(),
                new RunTools.Context(sources, skills), run.getMaxSteps(), run.getMaxTokens(), deadline);
    }

    private AgentService.Content draftConfig(Agent agent) {
        return agentVersions.findByItemIdAndOpenFlag(agent.getId(), 1)
                .map(v -> JSON.readValue(((ItemVersion) v).getSnapshotJson(), AgentService.Content.class))
                .orElseGet(() -> AgentService.contentOf(agent));
    }

    private SkillRef skillRef(Skill s, boolean draft) {
        SkillService.Content c = SkillService.contentOf(s);
        if (draft) {
            c = skillVersions.findByItemIdAndOpenFlag(s.getId(), 1)
                    .map(v -> JSON.readValue(v.getSnapshotJson(), SkillService.Content.class)).orElse(c);
        }
        return new SkillRef(c.name(), c.triggerText(), c.instructions(), c.outputFormat(), c.tools() == null || c.tools().isEmpty() ? EnumSet.noneOf(com.agenticpilot.skill.Tool.class) : EnumSet.copyOf(c.tools()));
    }

    // ----- the loop -----

    private void loop(Setup s, State st, String rawInput) {
        String input = checkTaskInput(s, st, rawInput);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user(RunPrompt.firstMessage(s.inputLabel(), input)));
        int textOnly = 0;

        while (true) {
            checkStop(s, st);
            long started = System.nanoTime();
            ModelResponse response = gateway.generate(s.tier(), s.region(), s.system(), shorten(messages), tools.definitions(s.toolContext()));
            long ms = (System.nanoTime() - started) / 1_000_000;
            st.modelTurns++;
            st.tokensIn += response.inputTokens();
            st.tokensOut += response.outputTokens();
            String text = response.text() == null ? "" : response.text();
            String shown = GuardrailEngine.checkOutput(text, s.guard(), List.of()).text();
            step(s, st, StepType.MODEL, null, null, shown.isBlank() ? "(asks for tools)" : shown, response.inputTokens(), response.outputTokens(), ms);

            if (response.toolCalls().isEmpty()) {
                if (++textOnly >= 2) {
                    String last = finalText(s, st, text);
                    step(s, st, StepType.FINISH, null, null, last, 0, 0, 0);
                    throw new StopRun(StopReason.DONE, last, "The model answered without calling finish.");
                }
                messages.add(ChatMessage.assistant(text.isBlank() ? "(no answer)" : text));
                messages.add(ChatMessage.user(RunPrompt.NUDGE));
                continue;
            }
            textOnly = 0;
            messages.add(ChatMessage.assistantWithTools(text, response.toolCalls()));
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                RunTools.Outcome outcome = handle(s, st, call);
                if (outcome.finish()) {
                    StopReason reason = "done".equals(outcome.finishStatus()) ? StopReason.DONE : StopReason.CANNOT_COMPLETE;
                    String last = finalText(s, st, outcome.content());
                    step(s, st, StepType.FINISH, null, null, last, 0, 0, 0);
                    throw new StopRun(reason, last, null);
                }
                results.add(new ToolResult(call.id(), outcome.content(), outcome.error()));
            }
            messages.add(ChatMessage.toolResults(results));
        }
    }

    /** Stops the run when a user asked for it or a limit is used up. Checked before every model call. */
    private void checkStop(Setup s, State st) {
        if (cancelRequested(s.runId())) {
            throw new StopRun(StopReason.STOPPED_BY_USER, null, null);
        }
        if (Instant.now().isAfter(s.deadline())) {
            throw new StopRun(StopReason.TIME_LIMIT, null, null);
        }
        if (st.modelTurns >= s.maxSteps()) {
            throw new StopRun(StopReason.STEP_LIMIT, null, null);
        }
        if (st.tokensIn + st.tokensOut >= s.maxTokens()) {
            throw new StopRun(StopReason.TOKEN_LIMIT, null, null);
        }
    }

    /** Checks the task input like a chat message. A block ends the run; a rule that needs approval pauses it. Stores the masked input. */
    private String checkTaskInput(Setup s, State st, String rawInput) {
        AgentGuardContext ctx = s.guard();
        while (true) {
            InputResult result = GuardrailEngine.checkInput(rawInput, ctx);
            if (result instanceof InputResult.Proceed proceed) {
                record(s, st, proceed.decisions(), null);
                st.notes.addAll(proceed.notes());
                jdbc.update("update agent_run set input = ?, version = version + 1 where id = ?", cut(proceed.text(), 1000), s.runId());
                return proceed.text();
            }
            InputResult.Stop stop = (InputResult.Stop) result;
            UUID pendingId = record(s, st, stop.decisions(), stop.outcome() == AuditOutcome.NEEDS_APPROVAL ? stop.pendingDescription() : null);
            if (stop.outcome() == AuditOutcome.BLOCKED) {
                throw new StopRun(StopReason.BLOCKED, stop.reply(), "The input was blocked by a guardrail.");
            }
            awaitApproval(s, st, pendingId, "The task needs approval before the agent starts.");
            ctx = afterApproval(ctx, stop.decisions());
        }
    }

    // ----- tools -----

    private RunTools.Outcome handle(Setup s, State st, ToolCall call) {
        step(s, st, StepType.TOOL_CALL, call.name(), call.inputJson(), null, 0, 0, 0);
        long started = System.nanoTime();
        RunTools.Outcome outcome = tools.execute(call, s.toolContext());
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (outcome.finish()) {
            return outcome;
        }
        String content = outcome.content();
        boolean error = outcome.error();
        boolean refused = outcome.refused();
        if (outcome.loadedSkill() != null) {
            step(s, st, StepType.SKILL, call.name(), null, outcome.loadedSkill().name(), 0, 0, ms);
        } else if (outcome.data()) {
            DataCheck checked = checkData(s, st, content);
            content = checked.text();
            if (checked.blocked()) {
                error = true;
                refused = true;
            }
        }
        if (content.length() > tools.maxResultChars()) {
            content = content.substring(0, tools.maxResultChars()) + "\n(shortened: the result was longer than " + tools.maxResultChars() + " characters)";
        }
        if (outcome.loadedSkill() == null) {
            step(s, st, refused ? StepType.REFUSED : StepType.TOOL_RESULT, call.name(), null, content, 0, 0, ms);
        }
        if (refused && ++st.refused >= props.maxRefused()) {
            throw new StopRun(StopReason.REFUSED_TOO_OFTEN, null, "The model asked for things it may not use " + st.refused + " times.");
        }
        return new RunTools.Outcome(content, error, refused, outcome.data(), outcome.loadedSkill(), false, null);
    }

    private record DataCheck(String text, boolean blocked) {
    }

    /** Data from a source is masked, filtered and checked before the model sees it. */
    private DataCheck checkData(Setup s, State st, String data) {
        AgentGuardContext ctx = s.guard();
        String text = data;
        while (true) {
            InputResult result = GuardrailEngine.checkData(text, ctx);
            if (result instanceof InputResult.Proceed proceed) {
                record(s, st, proceed.decisions(), null);
                GuardrailEngine.PassageResult filtered = GuardrailEngine.filterPassage(proceed.text(), s.hiddenProtection());
                if (filtered.flagged()) {
                    List<Decision> flagged = List.of(Decision.of(Decision.Kind.HIDDEN_INSTRUCTION, AuditOutcome.MASKED, "Instruction-like text in a result was ignored"));
                    record(s, st, flagged, null);
                    step(s, st, StepType.GUARDRAIL, null, null, "Instruction-like text in a result was ignored", 0, 0, 0);
                }
                return new DataCheck(filtered.text(), false);
            }
            InputResult.Stop stop = (InputResult.Stop) result;
            UUID pendingId = record(s, st, stop.decisions(), stop.outcome() == AuditOutcome.NEEDS_APPROVAL ? stop.pendingDescription() : null);
            if (stop.outcome() == AuditOutcome.BLOCKED) {
                step(s, st, StepType.GUARDRAIL, null, null, "A result was withheld by a guardrail", 0, 0, 0);
                return new DataCheck("This result was withheld by a guardrail. Do not guess what it contained.", true);
            }
            awaitApproval(s, st, pendingId, "A result needs approval before the agent may use it.");
            ctx = afterApproval(ctx, stop.decisions());
        }
    }

    /** The context for a second check after an approver allowed what stopped the first. */
    private static AgentGuardContext afterApproval(AgentGuardContext ctx, List<Decision> decisions) {
        Set<UUID> allowedRules = new java.util.HashSet<>();
        boolean approvals = true;
        boolean special = true;
        for (Decision d : decisions) {
            if (d.kind() == Decision.Kind.CUSTOM_RULE && d.ruleId() != null) {
                allowedRules.add(d.ruleId());
            } else if (d.kind() == Decision.Kind.APPROVAL_ACTION) {
                approvals = false;
            } else if (d.kind() == Decision.Kind.SPECIAL_DATA) {
                special = false;
            }
        }
        List<RuleSpec> rules = ctx.rules().stream().filter(r -> !allowedRules.contains(r.id())).toList();
        return new AgentGuardContext(ctx.personalData(), special ? ctx.specialData() : SpecialDataMode.ALLOW,
                approvals ? ctx.approvals() : Set.of(), ctx.maxRecords(), ctx.hiddenInstructionProtection(), rules);
    }

    private void awaitApproval(Setup s, State st, UUID pendingId, String why) {
        if (pendingId == null) {
            throw new StopRun(StopReason.BLOCKED, null, "A guardrail stopped the run.");
        }
        if (++st.approvals > props.maxApprovalsPerRun()) {
            throw new StopRun(StopReason.DENIED, null, "Too many approvals were needed in one run.");
        }
        step(s, st, StepType.PENDING, null, null, why, 0, 0, 0);
        jdbc.update("update agent_run set status = 'WAITING_FOR_APPROVAL', pending_action_id = ?, version = version + 1 where id = ?", pendingId, s.runId());
        Instant lastBeat = Instant.now();
        while (true) {
            if (Duration.between(lastBeat, Instant.now()).toSeconds() >= 20) {
                jdbc.update("update agent_run set last_step_at = ?, version = version + 1 where id = ?", Timestamp.from(Instant.now()), s.runId());
                lastBeat = Instant.now();
            }
            PendingAction action = pendingActions.findById(pendingId).orElseThrow();
            if (action.getStatus() == PendingActionStatus.APPROVED) {
                jdbc.update("update agent_run set status = 'RUNNING', pending_action_id = null, last_step_at = ?, version = version + 1 where id = ?",
                        Timestamp.from(Instant.now()), s.runId());
                return;
            }
            if (action.getStatus() == PendingActionStatus.DENIED) {
                throw new StopRun(StopReason.DENIED, null, "An approver denied the request.");
            }
            if (cancelRequested(s.runId())) {
                throw new StopRun(StopReason.STOPPED_BY_USER, null, null);
            }
            if (Instant.now().isAfter(s.deadline())) {
                throw new StopRun(StopReason.TIME_LIMIT, null, "Nobody decided in time.");
            }
            try {
                Thread.sleep(props.approvalPollMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopRun(StopReason.INTERRUPTED, null, null);
            }
        }
    }

    // ----- results and bookkeeping -----

    /** The result passes the output checks (personal data, record limit, notes) before it is shown or stored. */
    private String finalText(Setup s, State st, String result) {
        GuardrailEngine.OutputResult out = GuardrailEngine.checkOutput(result, s.guard(), st.notes);
        record(s, st, out.decisions(), null);
        return out.text();
    }

    private UUID record(Setup s, State st, List<Decision> decisions, String pendingDescription) {
        if (decisions.isEmpty()) {
            return null;
        }
        UUID id = tx.execute(status -> recorder.record(s.actor(), s.agentId(), s.agentName(), s.runId(), decisions, pendingDescription, null, s.startedBy()));
        for (Decision d : decisions) {
            step(s, st, StepType.GUARDRAIL, null, null, d.ruleName() == null ? d.detail() : d.detail() + " (" + d.ruleName() + ")", 0, 0, 0);
        }
        return id;
    }

    private void step(Setup s, State st, StepType type, String tool, String arguments, String content, int tokensIn, int tokensOut, long ms) {
        st.ordinal++;
        steps.save(new AgentRunStep(s.runId(), st.ordinal, type, tool, arguments, content, tokensIn, tokensOut, ms));
        jdbc.update("update agent_run set step_count = ?, tokens_in = ?, tokens_out = ?, last_step_at = ?, version = version + 1 where id = ?",
                st.modelTurns, st.tokensIn, st.tokensOut, Timestamp.from(Instant.now()), s.runId());
    }

    private boolean cancelRequested(UUID runId) {
        Boolean b = jdbc.queryForObject("select cancel_requested from agent_run where id = ?", Boolean.class, runId);
        return Boolean.TRUE.equals(b);
    }

    /** Older tool results are cut down when the conversation nears the prompt limit, so a long run does not fail on size. */
    private List<ChatMessage> shorten(List<ChatMessage> messages) {
        int limit = (int) (gateway.maxPromptChars() * 0.7);
        int size = messages.stream().mapToInt(ChatMessage::size).sum();
        if (size <= limit) {
            return List.copyOf(messages); // a snapshot: the conversation keeps growing after the call
        }
        List<ChatMessage> out = new ArrayList<>(messages);
        for (int i = 0; i < out.size() - 3 && size > limit; i++) {
            ChatMessage m = out.get(i);
            if (m.toolResults().isEmpty()) {
                continue;
            }
            List<ToolResult> cut = m.toolResults().stream().map(r -> new ToolResult(r.toolCallId(),
                    r.content().length() > 200 ? r.content().substring(0, 200) + " (shortened)" : r.content(), r.error())).toList();
            ChatMessage replacement = ChatMessage.toolResults(cut);
            size += replacement.size() - m.size();
            out.set(i, replacement);
        }
        return List.copyOf(out);
    }

    private void conclude(Setup s, State st, StopReason reason, String result, String detail) {
        finish(s.runId(), reason, result, detail, s.storeNothing(), st);
        AuditOutcome outcome = reason == StopReason.BLOCKED || reason == StopReason.DENIED ? AuditOutcome.BLOCKED : AuditOutcome.ALLOWED;
        try {
            audit.recordAs(s.actor(), s.agentId(), s.agentName(), "Run ended: " + reason.name().toLowerCase().replace('_', ' ') + (s.draft() ? " (draft)" : ""),
                    outcome, null, Map.of("runId", s.runId().toString(), "modelTurns", st.modelTurns, "tokens", st.tokensIn + st.tokensOut));
            jdbc.update("update agent set last_run = ?, run_count = run_count + 1 where id = ?", Timestamp.from(Instant.now()), s.agentId());
        } catch (RuntimeException e) {
            log.warn("Run {} ended but its audit entry failed: {}", s.runId(), e.getClass().getSimpleName());
        }
    }

    /** Marks the run finished (once) and, when the agent does not store anything, drops the texts. */
    void finish(UUID runId, StopReason reason, String result, String detail, boolean storeNothing, State st) {
        int changed = jdbc.update("""
                update agent_run set status = 'FINISHED', stop_reason = ?, result = ?, detail = ?, ended_at = ?, last_step_at = ?,
                pending_action_id = null, version = version + 1 where id = ? and status <> 'FINISHED'
                """, reason.name(), result == null ? null : cut(result, 10000), detail == null ? null : cut(detail, 500),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId);
        if (changed > 0 && storeNothing) {
            steps.deleteByRunId(runId);
            jdbc.update("update agent_run set input = null, result = null, version = version + 1 where id = ?", runId);
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
