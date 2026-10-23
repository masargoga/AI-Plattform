package com.agenticpilot.runtime;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentVersionRepository;
import com.agenticpilot.governance.Lifecycle;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.AgentGuardContext;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.GuardrailReader;
import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.guardrail.GuardrailEngine;
import com.agenticpilot.guardrail.InputResult;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.StreamingOutputGuard;
import com.agenticpilot.runtime.ChatReply.Status;
import com.agenticpilot.runtime.ChatReply.TraceItem;
import com.agenticpilot.runtime.PromptAssembler.Passage;
import com.agenticpilot.runtime.PromptAssembler.SkillInfo;
import com.agenticpilot.runtime.provider.ChatMessage;
import com.agenticpilot.runtime.provider.ModelException;
import com.agenticpilot.runtime.provider.ModelResponse;
import com.agenticpilot.skill.Skill;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one message through an agent: guardrail check, retrieval, prompt assembly, model call and output checks. It serves
 * the test chat and the API; who is calling is described by a {@link ChatActor}. The database transaction is closed before
 * the model is called, so a slow model never holds a connection.
 *
 * <p>Privacy: the raw user text goes nowhere except into the engine. Stored messages and the model prompt contain the
 * masked text; a message that was stopped is stored as a placeholder; audit entries and logs never contain message text.
 *
 * <p>This class does no permission checks of its own. Callers authenticate first: {@link AgentChatService} for signed-in
 * users and the API layer for API keys.
 */
@Service
public class ChatPipeline {

    public static final int MAX_MESSAGE_CHARS = 4000;
    static final int PASSAGES = 5;
    static final int HISTORY_MESSAGES = 20;
    static final String NOT_STORED = "[message not stored]";

    /** Text of the NOTICE trace items that explain why no answer was produced. */
    public static final String NOTICE_PAUSED = "Agent paused";
    public static final String NOTICE_EU = "EU-only: no EU endpoint";
    public static final String NOTICE_NO_MODEL = "No model connection";
    public static final String NOTICE_MODEL_DOWN = "Model unavailable";
    public static final String NOTICE_NOT_APPROVED = "Agent not approved";

    /** Receives the parts of a streamed answer in the order they happen. */
    public interface StreamListener {

        /** The guardrail decisions on the input, before any model text. */
        void onDecisions(List<Decision> decisions);

        /** Text that has passed the output guardrails and may be released. */
        void onText(String text);
    }

    private static final Logger log = LoggerFactory.getLogger(ChatPipeline.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentRepository agents;
    private final GuardrailReader guardrails;
    private final GuardrailContextFactory contexts;
    private final Retriever retriever;
    private final ModelGateway gateway;
    private final AuditService audit;
    private final GuardrailRecorder recorder;
    private final ConversationRepository conversations;
    private final StoredMessageRepository messages;
    private final SkillRepository skillRepository;
    private final AgentVersionRepository agentVersions;
    private final TransactionTemplate tx;

    public ChatPipeline(AgentRepository agents, GuardrailReader guardrails, GuardrailContextFactory contexts, Retriever retriever,
                        ModelGateway gateway, AuditService audit, GuardrailRecorder recorder, ConversationRepository conversations,
                        StoredMessageRepository messages, SkillRepository skillRepository, AgentVersionRepository agentVersions,
                        PlatformTransactionManager txManager) {
        this.agents = agents;
        this.guardrails = guardrails;
        this.contexts = contexts;
        this.retriever = retriever;
        this.gateway = gateway;
        this.audit = audit;
        this.recorder = recorder;
        this.conversations = conversations;
        this.messages = messages;
        this.skillRepository = skillRepository;
        this.agentVersions = agentVersions;
        this.tx = new TransactionTemplate(txManager);
    }

    /** What phase one hands to the model call. */
    private record Prepared(UUID agentId, String agentName, UUID conversationId, UUID requestId, PromptAssembler.Prompt prompt, AgentGuardContext ctx,
                            List<String> notes, List<String> skillNames, com.agenticpilot.agent.ModelTier tier, Region region,
                            List<Decision> decisions, List<Passage> passages, boolean draft, List<String> unavailable) {
    }

    /** Either a finished reply (stopped or refused) or a prepared model call. */
    private record Phase(ChatReply reply, Prepared prepared) {
    }

    /** The answer after the output checks. */
    private record Output(String text, List<Decision> decisions, String skillName) {
    }

    public ChatReply send(ChatActor actor, UUID agentId, UUID conversationId, String userText) {
        return run(actor, agentId, conversationId, userText, null);
    }

    /** Like {@link #send}, but releases the answer through the listener as it is produced (and checked). */
    public ChatReply sendStreaming(ChatActor actor, UUID agentId, UUID conversationId, String userText, StreamListener listener) {
        return run(actor, agentId, conversationId, userText, listener);
    }

    private ChatReply run(ChatActor actor, UUID agentId, UUID conversationId, String userText, StreamListener listener) {
        String text = validate(userText);
        Phase phase = tx.execute(status -> prepare(actor, agentId, conversationId, text));
        if (phase.reply() != null) {
            if (listener != null) {
                listener.onDecisions(phase.reply().decisions());
                listener.onText(phase.reply().text());
            }
            return phase.reply();
        }
        Prepared p = phase.prepared();
        if (listener != null) {
            listener.onDecisions(p.decisions());
        }

        Output output;
        ModelResponse response;
        try {
            if (listener == null) {
                response = gateway.generate(p.tier(), p.region(), p.prompt().system(), p.prompt().messages());
                PromptAssembler.Answer answer = PromptAssembler.splitSkillLine(response.text(), p.skillNames());
                GuardrailEngine.OutputResult checked = GuardrailEngine.checkOutput(answer.text(), p.ctx(), p.notes());
                output = new Output(checked.text(), checked.decisions(), answer.skillName());
            } else {
                StreamingOutputGuard guard = new StreamingOutputGuard(p.ctx(), p.notes(), p.skillNames());
                response = gateway.stream(p.tier(), p.region(), p.prompt().system(), p.prompt().messages(), piece -> {
                    String released = guard.accept(piece);
                    if (!released.isEmpty()) {
                        listener.onText(released);
                    }
                });
                StreamingOutputGuard.Result result = guard.finish();
                if (!result.tail().isEmpty()) {
                    listener.onText(result.tail());
                }
                output = new Output(result.fullText(), result.decisions(), result.skillName());
            }
        } catch (ModelException e) {
            log.warn("Model call failed for agent {} (request {}): {}", p.agentId(), p.requestId(), e.getMessage());
            ChatReply failed = new ChatReply(p.conversationId(), Status.UNAVAILABLE, "The model could not answer right now. Please try again in a moment.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, NOTICE_MODEL_DOWN)), null, p.decisions(), p.requestId());
            return failed;
        }
        return tx.execute(status -> finish(actor, p, response, output));
    }

    private static String validate(String userText) {
        String text = userText == null ? "" : userText.strip();
        if (text.isEmpty()) {
            throw new InvalidInputException("error.chat.empty");
        }
        if (text.length() > MAX_MESSAGE_CHARS) {
            throw new InvalidInputException("error.tooLong", MAX_MESSAGE_CHARS);
        }
        return text;
    }

    // ----- phase one: checks, conversation, retrieval, prompt -----

    private Phase prepare(ChatActor actor, UUID agentId, UUID conversationId, String text) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        UUID requestId = UUID.randomUUID();
        boolean draft = actor.draft();
        if (!draft && !agent.isUsable()) {
            return done(new ChatReply(conversationId, Status.REFUSED, "This agent is not approved yet. A second Admin has to approve it before it can be used.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, NOTICE_NOT_APPROVED)), null, List.of(), requestId));
        }
        if (agent.getStatus() == AgentStatus.PAUSED) {
            return done(new ChatReply(conversationId, Status.REFUSED, "This agent is paused. Activate it to try it.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, NOTICE_PAUSED)), null, List.of(), requestId));
        }
        AgentGuardrail g = guardrails.get(agentId);
        AgentGuardContext ctx = contexts.build(g);
        Conversation conversation = conversation(actor, agent, g, conversationId);

        InputResult input = GuardrailEngine.checkInput(text, ctx);
        if (input instanceof InputResult.Stop stop) {
            UUID pendingId = recorder.record(actor.auditName(), agent.getId(), agent.getName(), requestId, stop.decisions(),
                    stop.outcome() == AuditOutcome.NEEDS_APPROVAL ? stop.pendingDescription() : null,
                    conversation == null ? null : conversation.getId(), actor.user());
            List<TraceItem> trace = trace(stop.decisions());
            Status status = stop.outcome() == AuditOutcome.BLOCKED ? Status.BLOCKED : Status.WAITING_FOR_APPROVAL;
            store(conversation, NOT_STORED, stop.reply(), trace);
            touch(agent, conversation != null && messages.countByConversationId(conversation.getId()) <= 2);
            return done(new ChatReply(conversation == null ? null : conversation.getId(), status, stop.reply(), trace, pendingId, stop.decisions(), requestId));
        }
        InputResult.Proceed proceed = (InputResult.Proceed) input;

        var refusal = gateway.refusalFor(g.getRegion());
        if (refusal.isPresent()) {
            UUID convId = conversation == null ? null : conversation.getId();
            if (refusal.get() == ModelGateway.Refusal.EU_NOT_AVAILABLE) {
                audit.recordAs(actor.auditName(), agent.getId(), agent.getName(),
                        "Request refused: EU-only agent but no EU-approved model endpoint is configured", AuditOutcome.BLOCKED, null,
                        Map.of("requestId", requestId.toString()));
                return done(new ChatReply(convId, Status.REFUSED,
                        "This agent is set to EU-only processing, but no EU-approved model endpoint is configured. Ask an operator to set one up.",
                        List.of(new TraceItem(TraceItem.Type.NOTICE, NOTICE_EU)), null, List.of(), requestId));
            }
            return done(new ChatReply(convId, Status.UNAVAILABLE,
                    "No model connection is configured yet. Ask an operator to set AGENTICPILOT_MODEL_API_KEY.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, NOTICE_NO_MODEL)), null, List.of(), requestId));
        }

        List<ChatMessage> history = new ArrayList<>();
        if (conversation != null) {
            List<StoredMessage> stored = messages.findByConversationIdOrderByOrdinal(conversation.getId());
            for (StoredMessage m : stored.subList(Math.max(0, stored.size() - HISTORY_MESSAGES), stored.size())) {
                history.add(new ChatMessage(m.getRole(), m.getText()));
            }
        }
        store(conversation, proceed.text(), null, null);

        List<Decision> decisions = new ArrayList<>(proceed.decisions());
        List<Passage> passages = new ArrayList<>();
        boolean flagged = false;
        AgentService.Content config = draft ? draftConfig(agent) : null;
        Collection<UUID> sourceIds = draft ? config.sourceIds()
                : agent.getSources().stream().map(com.agenticpilot.source.DataSource::getId).toList();
        List<Retriever.Retrieved> retrieved = retriever.retrieveFrom(sourceIds, proceed.text(), PASSAGES);
        List<String> unavailable = retriever.unavailable(sourceIds);
        for (Retriever.Retrieved r : retrieved) {
            GuardrailEngine.PassageResult filtered = GuardrailEngine.filterPassage(r.text(), g.isHiddenInstructionProtection());
            flagged |= filtered.flagged();
            if (!filtered.text().isBlank()) {
                passages.add(new Passage(r.sourceName(), r.documentTitle(), filtered.text()));
            }
        }
        if (flagged) {
            decisions.add(Decision.of(Decision.Kind.HIDDEN_INSTRUCTION, AuditOutcome.MASKED, "Instruction-like text in a document was ignored"));
        }

        // Only approved skills are used. When testing a draft, the skills of the draft count, whatever their state (except retired).
        Collection<Skill> candidates = draft ? skillRepository.findAllById(config.skillIds()) : agent.getSkills();
        List<Skill> skills = candidates.stream().filter(sk -> draft ? sk.getLifecycle() != Lifecycle.RETIRED : sk.isUsable())
                .sorted(Comparator.comparing(Skill::getName)).toList();
        String agentName = draft ? config.name() : agent.getName();
        String instructions = draft ? config.instructions() : agent.getInstructions();
        com.agenticpilot.agent.ModelTier tier = draft && config.modelTier() != null ? config.modelTier() : agent.getModelTier();
        List<SkillInfo> skillInfos = skills.stream().map(s -> new SkillInfo(s.getName(), s.getTriggerText(), s.getInstructions(), s.getOutputFormat())).toList();
        PromptAssembler.Prompt prompt = PromptAssembler.assemble(agentName, instructions, g.getPurpose(), skillInfos, passages,
                history, proceed.text(), gateway.maxPromptChars(), unavailable);
        touch(agent, conversation != null && history.isEmpty());
        return new Phase(null, new Prepared(agentId, agentName, conversation == null ? null : conversation.getId(), requestId, prompt, ctx,
                proceed.notes(), skills.stream().map(Skill::getName).toList(), tier, g.getRegion(), decisions, prompt.usedPassages(), draft, unavailable));
    }

    // ----- phase three: audit, storage -----

    private ChatReply finish(ChatActor actor, Prepared p, ModelResponse response, Output output) {
        List<Decision> all = new ArrayList<>(p.decisions());
        all.addAll(output.decisions());

        Agent agent = agents.findById(p.agentId()).orElseThrow(() -> new NotFoundException("Agent"));
        Set<String> sourceNames = new LinkedHashSet<>();
        p.passages().forEach(x -> sourceNames.add(x.sourceName() + " / " + x.documentTitle()));
        recorder.record(actor.auditName(), agent.getId(), agent.getName(), p.requestId(), all, null, null, actor.user());
        audit.recordAs(actor.auditName(), agent.getId(), agent.getName(), "Answered a question" + (p.draft() ? " (testing a draft)" : "") + (p.passages().isEmpty() ? ""
                        : " using " + p.passages().size() + (p.passages().size() == 1 ? " passage" : " passages")),
                AuditOutcome.ALLOWED, null, Map.of("requestId", p.requestId().toString(), "inputTokens", response.inputTokens(), "outputTokens", response.outputTokens()));

        List<TraceItem> trace = new ArrayList<>(trace(all));
        if (output.skillName() != null) {
            trace.add(new TraceItem(TraceItem.Type.SKILL, output.skillName()));
        }
        sourceNames.forEach(s -> trace.add(new TraceItem(TraceItem.Type.SOURCE, s)));
        p.unavailable().forEach(s -> trace.add(new TraceItem(TraceItem.Type.NOTICE, "Source unavailable: " + s)));
        if (p.conversationId() != null) {
            Conversation c = conversations.findById(p.conversationId()).orElse(null);
            store(c, null, output.text(), trace);
        }
        return new ChatReply(p.conversationId(), Status.ANSWERED, output.text(), trace, null, all, p.requestId());
    }

    // ----- helpers -----

    /** The configuration being edited: the open version, else the live fields. */
    private AgentService.Content draftConfig(Agent agent) {
        return agentVersions.findByItemIdAndOpenFlag(agent.getId(), 1)
                .map(v -> JSON.readValue(v.getSnapshotJson(), AgentService.Content.class))
                .orElseGet(() -> AgentService.contentOf(agent));
    }

    private static Phase done(ChatReply reply) {
        return new Phase(reply, null);
    }

    private Conversation conversation(ChatActor actor, Agent agent, AgentGuardrail g, UUID conversationId) {
        if (conversationId != null) {
            Conversation c = conversations.findById(conversationId).orElseThrow(() -> new NotFoundException("Conversation"));
            boolean owner = actor.apiKeyId() != null
                    ? actor.apiKeyId().equals(c.getApiKeyId())
                    : (c.getStartedBy() != null && actor.user() != null && c.getStartedBy().getId().equals(actor.user().getId())) || (actor.user() != null && actor.user().isAdmin());
            if (!c.getAgentId().equals(agent.getId()) || c.getChannel() != actor.channel() || !owner) {
                throw new AccessDeniedException("Not your conversation");
            }
            return c;
        }
        if (g.getRetention() == Retention.NONE) {
            return null; // "Do not store": nothing is persisted and there is no memory between messages
        }
        Instant now = Instant.now();
        Instant expires = g.getRetention() == Retention.UNLIMITED ? null : now.plus(Duration.ofDays(g.getRetention().days()));
        return conversations.save(new Conversation(agent.getId(), actor.channel(), actor.user(), now, expires, actor.apiKeyId()));
    }

    private void store(Conversation c, String userText, String assistantText, List<TraceItem> trace) {
        if (c == null) {
            return;
        }
        int next = messages.countByConversationId(c.getId());
        if (userText != null) {
            messages.save(new StoredMessage(c.getId(), next++, "user", userText, null));
        }
        if (assistantText != null) {
            messages.save(new StoredMessage(c.getId(), next, "assistant", assistantText, trace == null ? null : JSON.writeValueAsString(trace)));
        }
    }

    private void touch(Agent agent, boolean newConversation) {
        agent.recordRun(Instant.now(), agent.getRunCount() + (newConversation ? 1 : 0));
    }

    private static List<TraceItem> trace(List<Decision> decisions) {
        List<TraceItem> trace = new ArrayList<>();
        for (Decision d : decisions) {
            String label = switch (d.kind()) {
                case SPECIAL_DATA -> d.outcome() == AuditOutcome.BLOCKED ? "Special data blocked" : "Sent to human review";
                case PERSONAL_DATA -> d.outcome() == AuditOutcome.BLOCKED ? "Personal data blocked" : "Personal data masked";
                case APPROVAL_ACTION -> "Waiting for approval";
                case CUSTOM_RULE -> d.ruleName();
                case HIDDEN_INSTRUCTION -> "Hidden instruction ignored";
                case RECORD_LIMIT -> "Answer shortened";
            };
            trace.add(new TraceItem(d.outcome() == AuditOutcome.NEEDS_APPROVAL ? TraceItem.Type.WAITING : TraceItem.Type.GUARDRAIL, label));
        }
        return trace;
    }
}
