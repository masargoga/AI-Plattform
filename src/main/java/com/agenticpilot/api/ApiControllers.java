package com.agenticpilot.api;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentKind;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.api.ApiDtos.AgentList;
import com.agenticpilot.api.ApiDtos.AgentSummary;
import com.agenticpilot.api.ApiDtos.ApprovalResponse;
import com.agenticpilot.api.ApiDtos.CheckRequest;
import com.agenticpilot.api.ApiDtos.CheckResponse;
import com.agenticpilot.api.ApiDtos.DecisionDto;
import com.agenticpilot.api.ApiDtos.MatchedRule;
import com.agenticpilot.api.ApiDtos.MessageRequest;
import com.agenticpilot.api.ApiDtos.ApprovalRef;
import com.agenticpilot.api.ApiDtos.MessageResponse;
import com.agenticpilot.api.ApiDtos.RunRequest;
import com.agenticpilot.api.ApiDtos.RunResponse;
import com.agenticpilot.api.ApiDtos.RunStarted;
import com.agenticpilot.api.ApiDtos.RunStepDto;
import com.agenticpilot.run.AgentRun;
import com.agenticpilot.run.AgentRunRepository;
import com.agenticpilot.run.AgentRunStepRepository;
import com.agenticpilot.run.RunLauncher;
import com.agenticpilot.run.RunRefusedException;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.runtime.BackgroundExecutor;
import com.agenticpilot.runtime.ChatActor;
import com.agenticpilot.runtime.ChatPipeline;
import com.agenticpilot.runtime.ChatReply;
import com.agenticpilot.runtime.GuardrailCheckService;
import com.agenticpilot.runtime.PendingAction;
import com.agenticpilot.runtime.PendingActionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.annotation.Transactional;

/** REST API v1. Every call is authenticated with an agent's API key; a key can only reach its own agent. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AgenticPilot API v1", description = "Run managed agents and check text against guardrails. Authenticate with `Authorization: Bearer <key>`.")
class ApiControllers {

    private static final Logger log = LoggerFactory.getLogger(ApiControllers.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;
    private static final int MAX_STREAMS_PER_KEY = 4;

    private final java.util.concurrent.ConcurrentHashMap<UUID, AtomicInteger> streams = new java.util.concurrent.ConcurrentHashMap<>();

    private final AgentRepository agents;
    private final ChatPipeline pipeline;
    private final GuardrailCheckService checks;
    private final PendingActionRepository pendingActions;
    private final BackgroundExecutor executor;
    private final RunLauncher launcher;
    private final AgentRunRepository runs;
    private final AgentRunStepRepository runSteps;

    ApiControllers(AgentRepository agents, ChatPipeline pipeline, GuardrailCheckService checks, PendingActionRepository pendingActions,
                   BackgroundExecutor executor, RunLauncher launcher, AgentRunRepository runs, AgentRunStepRepository runSteps) {
        this.launcher = launcher;
        this.runs = runs;
        this.runSteps = runSteps;
        this.agents = agents;
        this.pipeline = pipeline;
        this.checks = checks;
        this.pendingActions = pendingActions;
        this.executor = executor;
    }

    // ----- agents -----

    @GetMapping("/agents")
    @Operation(summary = "List the agents the key may use")
    @ApiResponse(responseCode = "200", description = "The agents")
    @Transactional(readOnly = true)
    public AgentList listAgents(@AuthenticationPrincipal ApiPrincipal principal) {
        Agent agent = agents.findById(principal.agentId()).filter(Agent::isUsable).orElse(null);
        if (agent == null) {
            return new AgentList(List.of());
        }
        return new AgentList(List.of(new AgentSummary(agent.getId(), agent.getName(), agent.getJob(),
                agent.getKind().name().toLowerCase(Locale.ROOT), agent.getStatus().name().toLowerCase(Locale.ROOT))));
    }

    // ----- messages -----

    @PostMapping("/agents/{agentId}/messages")
    @Operation(summary = "Send a message to a managed agent",
            description = "Returns the answer with the skills and sources used and every guardrail decision. With `stream: true` the answer arrives as "
                    + "server-sent events: `decision` events (input checks, first), `delta` events with text that already passed the output "
                    + "guardrails, then `done` with the complete response, or `error`. A request that needs approval answers 202 with an approval to poll.")
    @ApiResponse(responseCode = "200", description = "Answered or blocked by a guardrail (`status` tells which). With `stream: true`: `text/event-stream`.",
            content = {@Content(mediaType = "application/json", schema = @Schema(implementation = MessageResponse.class)),
                    @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "202", description = "Waiting for approval; poll `GET /api/v1/approvals/{id}`",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MessageResponse.class)))
    @ApiResponse(responseCode = "409", description = "The agent is paused or is an external agent",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "No model available (not configured, failed, or EU-only without an EU endpoint)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public Object sendMessage(@PathVariable UUID agentId, @RequestBody MessageRequest body, @AuthenticationPrincipal ApiPrincipal principal) {
        Agent agent = agent(principal, agentId);
        if (agent.getKind() != AgentKind.MANAGED) {
            throw new ApiException(HttpStatus.CONFLICT, "agent_not_managed", "This is an external agent. Use POST /api/v1/guardrails/check.");
        }
        if (body == null || body.message() == null) {
            throw new InvalidInputException("error.chat.empty");
        }
        ChatActor actor = ChatActor.api(principal.keyId(), principal.prefix());
        if (Boolean.TRUE.equals(body.stream())) {
            return stream(actor, agentId, body);
        }
        ChatReply reply = pipeline.send(actor, agentId, body.conversationId(), body.message());
        return toHttp(reply);
    }

    private SseEmitter stream(ChatActor actor, UUID agentId, MessageRequest body) {
        AtomicInteger running = streams.computeIfAbsent(actor.apiKeyId(), k -> new AtomicInteger());
        if (running.incrementAndGet() > MAX_STREAMS_PER_KEY) {
            running.decrementAndGet();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_streams",
                    "At most " + MAX_STREAMS_PER_KEY + " streams may run at the same time for one API key.");
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        boolean[] open = {true};
        executor.submit(() -> {
            try {
                ChatReply reply = pipeline.sendStreaming(actor, agentId, body.conversationId(), body.message(), new ChatPipeline.StreamListener() {
                    @Override
                    public void onDecisions(List<Decision> decisions) {
                        for (Decision d : decisions) {
                            send(emitter, open, "decision", ApiDtos.DecisionDto.of(d));
                        }
                    }

                    @Override
                    public void onText(String text) {
                        send(emitter, open, "delta", Map.of("text", text));
                    }
                });
                if (isFailure(reply)) {
                    send(emitter, open, "error", failure(reply));
                } else {
                    send(emitter, open, "done", toResponse(reply));
                }
                emitter.complete();
            } catch (InvalidInputException e) {
                send(emitter, open, "error", new ApiError("invalid_request", com.agenticpilot.common.Messages.english(e.getMessage(), e.getArgs()), null));
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Streaming message failed for agent {}: {}", agentId, e.getClass().getSimpleName());
                send(emitter, open, "error", new ApiError("internal_error", "The request could not be completed.", null));
                emitter.complete();
            } finally {
                running.decrementAndGet();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, boolean[] open, String event, Object data) {
        if (!open[0]) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            open[0] = false; // the client went away; the answer is still stored and audited
        }
    }

    private ResponseEntity<?> toHttp(ChatReply reply) {
        if (isFailure(reply)) {
            ApiError error = failure(reply);
            return ResponseEntity.status(statusOf(reply)).header("X-Request-Id", String.valueOf(reply.requestId())).body(error);
        }
        MessageResponse body = toResponse(reply);
        HttpStatus status = reply.status() == ChatReply.Status.WAITING_FOR_APPROVAL ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).header("X-Request-Id", String.valueOf(reply.requestId())).body(body);
    }

    private static boolean isFailure(ChatReply reply) {
        return reply.status() == ChatReply.Status.REFUSED || reply.status() == ChatReply.Status.UNAVAILABLE;
    }

    private static HttpStatus statusOf(ChatReply reply) {
        boolean paused = reply.traceTexts(ChatReply.TraceItem.Type.NOTICE).contains(ChatPipeline.NOTICE_PAUSED);
        return paused ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
    }

    private static ApiError failure(ChatReply reply) {
        var notices = reply.traceTexts(ChatReply.TraceItem.Type.NOTICE);
        String code = notices.contains(ChatPipeline.NOTICE_PAUSED) ? "agent_paused"
                : notices.contains(ChatPipeline.NOTICE_EU) ? "eu_endpoint_unavailable" : "model_unavailable";
        return new ApiError(code, reply.text(), reply.requestId() == null ? null : reply.requestId().toString());
    }

    private static MessageResponse toResponse(ChatReply reply) {
        String status = switch (reply.status()) {
            case ANSWERED -> "answered";
            case BLOCKED -> "blocked";
            default -> "needs_approval";
        };
        return new MessageResponse(reply.requestId(), reply.conversationId(), status, reply.text(),
                reply.traceTexts(ChatReply.TraceItem.Type.SKILL), reply.traceTexts(ChatReply.TraceItem.Type.SOURCE),
                reply.decisions().stream().map(DecisionDto::of).toList(), ApiDtos.approvalRef(reply.pendingActionId()));
    }

    // ----- guardrail check -----

    @PostMapping("/guardrails/check")
    @Operation(summary = "Check text against an agent's guardrails",
            description = "For external agents. Returns allow, mask, block or needs_approval, the transformed text and the rules that matched. "
                    + "No model is called. A needs_approval result creates a pending action; poll it with `GET /api/v1/approvals/{id}`.")
    @ApiResponse(responseCode = "200", description = "The verdict")
    public CheckResponse check(@RequestBody CheckRequest body, @AuthenticationPrincipal ApiPrincipal principal) {
        if (body == null || body.agentId() == null) {
            throw new InvalidInputException("error.invalidChoice");
        }
        agent(principal, body.agentId());
        GuardrailCheckService.Direction direction = body.direction() == null || body.direction().isBlank() ? GuardrailCheckService.Direction.INPUT
                : switch (body.direction().toLowerCase(Locale.ROOT)) {
                    case "input" -> GuardrailCheckService.Direction.INPUT;
                    case "output" -> GuardrailCheckService.Direction.OUTPUT;
                    default -> throw new InvalidInputException("error.invalidChoice");
                };
        GuardrailCheckService.Result r = checks.check("api:" + principal.prefix(), body.agentId(), body.text(), direction);
        List<MatchedRule> matched = r.decisions().stream().filter(d -> d.ruleId() != null)
                .map(d -> new MatchedRule(d.ruleId(), d.ruleName(), d.outcome().name().toLowerCase(Locale.ROOT))).toList();
        return new CheckResponse(r.requestId(), r.verdict().name().toLowerCase(Locale.ROOT), r.text(), r.message(), matched,
                r.decisions().stream().map(DecisionDto::of).toList(), r.notes(), ApiDtos.approvalRef(r.pendingActionId()));
    }

    // ----- runs -----

    @PostMapping("/agents/{agentId}/runs")
    @Operation(summary = "Start a run of a managed agent",
            description = "The agent works on the input in a loop until its goal is met or a limit stops it. Answers 202 with a run to poll with `GET /api/v1/runs/{id}`. "
                    + "Only approved agents run.")
    @ApiResponse(responseCode = "202", description = "The run was started", content = @Content(schema = @Schema(implementation = RunStarted.class)))
    @ApiResponse(responseCode = "409", description = "The agent is paused, not approved, or external",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "Too many runs are going", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "No model available", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RunStarted> startRun(@PathVariable UUID agentId, @RequestBody RunRequest body, @AuthenticationPrincipal ApiPrincipal principal) {
        agent(principal, agentId);
        if (body == null || body.input() == null) {
            throw new InvalidInputException("run.error.inputRequired");
        }
        try {
            UUID id = launcher.start(agentId, new RunLauncher.Starter(null, principal.keyId(), "api:" + principal.prefix()), body.input(), false);
            return ResponseEntity.status(HttpStatus.ACCEPTED).header("X-Request-Id", id.toString()).body(new RunStarted(id, "running", "/api/v1/runs/" + id));
        } catch (RunRefusedException e) {
            throw switch (e.code()) {
                case "external" -> new ApiException(HttpStatus.CONFLICT, "agent_not_managed", "This is an external agent. Use POST /api/v1/guardrails/check.");
                case "not_approved" -> new ApiException(HttpStatus.CONFLICT, "agent_not_approved", "This agent is not approved yet or has been retired.");
                case "paused" -> new ApiException(HttpStatus.CONFLICT, "agent_paused", "This agent is paused.");
                case "eu" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "eu_endpoint_unavailable", "This agent is EU-only, but no EU-approved model endpoint is configured.");
                case "no_model" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "model_unavailable", "No model connection is configured.");
                default -> new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_runs", "Too many runs are going. Try again in a moment.");
            };
        }
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Get a run", description = "Status, result and the steps so far. A key sees only runs of its own agent.")
    @ApiResponse(responseCode = "200", description = "The run", content = @Content(schema = @Schema(implementation = RunResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown, or it belongs to another agent", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @Transactional(readOnly = true)
    public RunResponse run(@PathVariable UUID runId, @AuthenticationPrincipal ApiPrincipal principal) {
        AgentRun run = runs.findById(runId).filter(r -> r.getAgentId().equals(principal.agentId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such run."));
        List<RunStepDto> timeline = runSteps.findByRunIdOrderByOrdinal(runId).stream()
                .map(s -> new RunStepDto(s.getOrdinal(), s.getType().name().toLowerCase(Locale.ROOT), s.getTool(), s.getArguments(), s.getContent(),
                        s.getTokensIn(), s.getTokensOut(), s.getDurationMs())).toList();
        ApprovalRef approval = run.getPendingActionId() == null ? null : ApiDtos.approvalRef(run.getPendingActionId());
        return new RunResponse(run.getId(), run.getAgentId(), run.getStatus().name().toLowerCase(Locale.ROOT),
                run.getStopReason() == null ? null : run.getStopReason().name().toLowerCase(Locale.ROOT), run.getResult(), run.getDetail(), run.getInput(),
                run.getStepCount(), run.getTokensIn() + run.getTokensOut(), run.getStartedAt(), run.getEndedAt(), approval, timeline);
    }

    // ----- approvals -----

    @GetMapping("/approvals/{approvalId}")
    @Operation(summary = "Poll a pending action", description = "Status is pending, approved or denied.")
    @ApiResponse(responseCode = "200", description = "The pending action")
    @ApiResponse(responseCode = "404", description = "Unknown, or it belongs to another agent",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @Transactional(readOnly = true)
    public ApprovalResponse approval(@PathVariable UUID approvalId, @AuthenticationPrincipal ApiPrincipal principal) {
        PendingAction action = pendingActions.findById(approvalId).filter(a -> a.getAgentId().equals(principal.agentId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such approval."));
        return ApprovalResponse.of(action);
    }

    // ----- helpers -----

    /** The agent behind the path or body id, which must be the key's own agent. Other ids look like they do not exist. */
    private Agent agent(ApiPrincipal principal, UUID agentId) {
        if (!principal.agentId().equals(agentId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This key belongs to another agent.");
        }
        Agent agent = agents.findById(agentId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such agent."));
        if (!agent.isUsable()) {
            throw new ApiException(HttpStatus.CONFLICT, "agent_not_approved", "This agent is not approved yet or has been retired.");
        }
        return agent;
    }
}
