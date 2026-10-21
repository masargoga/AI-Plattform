package com.agenticpilot.runtime;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.audit.AuditEntry;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.AgentGuardContext;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.CatchPattern;
import com.agenticpilot.guardrail.CustomRuleRepository;
import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.guardrail.GuardrailEngine;
import com.agenticpilot.guardrail.InputResult;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleSpec;
import com.agenticpilot.runtime.ChatReply.Status;
import com.agenticpilot.runtime.ChatReply.TraceItem;
import com.agenticpilot.runtime.PromptAssembler.Passage;
import com.agenticpilot.runtime.PromptAssembler.SkillInfo;
import com.agenticpilot.runtime.provider.ChatMessage;
import com.agenticpilot.runtime.provider.ModelException;
import com.agenticpilot.runtime.provider.ModelResponse;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.user.AppUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one message through an agent: guardrail check, retrieval, prompt assembly, model call and output checks. The
 * database transaction is closed before the model is called, so a slow model never holds a connection.
 *
 * <p>Privacy: the raw user text goes nowhere except into the engine. Stored messages and the model prompt contain the
 * masked text; a message that was stopped is stored as a placeholder; audit entries and logs never contain message text.
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AgentChatService {

    public static final int MAX_MESSAGE_CHARS = 4000;
    static final int PASSAGES = 5;
    static final int HISTORY_MESSAGES = 20;
    static final String NOT_STORED = "[message not stored]";

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentRepository agents;
    private final AgentGuardrailService guardrails;
    private final CustomRuleRepository rules;
    private final Retriever retriever;
    private final ModelGateway gateway;
    private final AuditService audit;
    private final PendingActionRepository pendingActions;
    private final ConversationRepository conversations;
    private final StoredMessageRepository messages;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;

    public AgentChatService(AgentRepository agents, AgentGuardrailService guardrails, CustomRuleRepository rules, Retriever retriever,
                            ModelGateway gateway, AuditService audit, PendingActionRepository pendingActions,
                            ConversationRepository conversations, StoredMessageRepository messages, CurrentUser currentUser,
                            PlatformTransactionManager txManager) {
        this.agents = agents;
        this.guardrails = guardrails;
        this.rules = rules;
        this.retriever = retriever;
        this.gateway = gateway;
        this.audit = audit;
        this.pendingActions = pendingActions;
        this.conversations = conversations;
        this.messages = messages;
        this.currentUser = currentUser;
        this.tx = new TransactionTemplate(txManager);
    }

    /** What phase one hands to the model call. */
    private record Prepared(UUID agentId, String agentName, UUID conversationId, UUID requestId, PromptAssembler.Prompt prompt, AgentGuardContext ctx,
                            List<String> notes, List<String> skillNames, com.agenticpilot.agent.ModelTier tier, Region region,
                            List<Decision> decisions, List<Passage> passages) {
    }

    /** Either a finished reply (stopped or refused) or a prepared model call. */
    private record Phase(ChatReply reply, Prepared prepared) {
    }

    public ChatReply send(UUID agentId, UUID conversationId, String userText) {
        String text = userText == null ? "" : userText.strip();
        if (text.isEmpty()) {
            throw new InvalidInputException("error.chat.empty");
        }
        if (text.length() > MAX_MESSAGE_CHARS) {
            throw new InvalidInputException("error.tooLong", MAX_MESSAGE_CHARS);
        }
        AppUser me = currentUser.require();

        Phase phase = tx.execute(status -> prepare(me, agentId, conversationId, text));
        if (phase.reply() != null) {
            return phase.reply();
        }
        Prepared p = phase.prepared();

        ModelResponse response;
        try {
            response = gateway.generate(p.tier(), p.region(), p.prompt().system(), p.prompt().messages());
        } catch (ModelException e) {
            log.warn("Model call failed for agent {} (request {}): {}", p.agentId(), p.requestId(), e.getMessage());
            return new ChatReply(p.conversationId(), Status.UNAVAILABLE, "The model could not answer right now. Please try again in a moment.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, "Model unavailable")), null);
        }
        return tx.execute(status -> finish(me, p, response));
    }

    // ----- phase one: checks, conversation, retrieval, prompt -----

    private Phase prepare(AppUser me, UUID agentId, UUID conversationId, String text) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        if (agent.getStatus() == AgentStatus.PAUSED) {
            return done(new ChatReply(conversationId, Status.REFUSED, "This agent is paused. Activate it to try it.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, "Agent paused")), null));
        }
        AgentGuardrail g = guardrails.get(agentId);
        AgentGuardContext ctx = context(g);
        UUID requestId = UUID.randomUUID();
        Conversation conversation = conversation(me, agent, g, conversationId);

        InputResult input = GuardrailEngine.checkInput(text, ctx);
        if (input instanceof InputResult.Stop stop) {
            UUID pendingId = auditDecisions(agent, requestId, stop.decisions(), stop.outcome() == AuditOutcome.NEEDS_APPROVAL ? stop.pendingDescription() : null,
                    conversation, me);
            List<TraceItem> trace = trace(stop.decisions());
            ChatReply.Status status = stop.outcome() == AuditOutcome.BLOCKED ? Status.BLOCKED : Status.WAITING_FOR_APPROVAL;
            store(conversation, NOT_STORED, stop.reply(), trace);
            touch(agent, conversation != null && messages.countByConversationId(conversation.getId()) <= 2);
            return done(new ChatReply(conversation == null ? null : conversation.getId(), status, stop.reply(), trace, pendingId));
        }
        InputResult.Proceed proceed = (InputResult.Proceed) input;

        var refusal = gateway.refusalFor(g.getRegion());
        if (refusal.isPresent()) {
            if (refusal.get() == ModelGateway.Refusal.EU_NOT_AVAILABLE) {
                audit.record(agent.getId(), agent.getName(), "Request refused: EU-only agent but no EU-approved model endpoint is configured",
                        AuditOutcome.BLOCKED, null, Map.of("requestId", requestId.toString()));
                return done(new ChatReply(conversation == null ? null : conversation.getId(), Status.REFUSED,
                        "This agent is set to EU-only processing, but no EU-approved model endpoint is configured. Ask an operator to set one up.",
                        List.of(new TraceItem(TraceItem.Type.NOTICE, "EU-only: no EU endpoint")), null));
            }
            return done(new ChatReply(conversation == null ? null : conversation.getId(), Status.UNAVAILABLE,
                    "No model connection is configured yet. Ask an operator to set AGENTICPILOT_MODEL_API_KEY.",
                    List.of(new TraceItem(TraceItem.Type.NOTICE, "No model connection")), null));
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
        for (Retriever.Retrieved r : retriever.retrieve(agentId, proceed.text(), PASSAGES)) {
            GuardrailEngine.PassageResult filtered = GuardrailEngine.filterPassage(r.text(), g.isHiddenInstructionProtection());
            flagged |= filtered.flagged();
            if (!filtered.text().isBlank()) {
                passages.add(new Passage(r.sourceName(), r.documentTitle(), filtered.text()));
            }
        }
        if (flagged) {
            decisions.add(Decision.of(Decision.Kind.HIDDEN_INSTRUCTION, AuditOutcome.MASKED, "Instruction-like text in a document was ignored"));
        }

        List<Skill> skills = agent.getSkills().stream().sorted(Comparator.comparing(Skill::getName)).toList();
        List<SkillInfo> skillInfos = skills.stream().map(s -> new SkillInfo(s.getName(), s.getTriggerText(), s.getInstructions(), s.getOutputFormat())).toList();
        PromptAssembler.Prompt prompt = PromptAssembler.assemble(agent.getName(), agent.getInstructions(), g.getPurpose(), skillInfos, passages,
                history, proceed.text(), gateway.maxPromptChars());
        touch(agent, conversation != null && history.isEmpty());
        return new Phase(null, new Prepared(agentId, agent.getName(), conversation == null ? null : conversation.getId(), requestId, prompt, ctx,
                proceed.notes(), skills.stream().map(Skill::getName).toList(), agent.getModelTier(), g.getRegion(), decisions, prompt.usedPassages()));
    }

    // ----- phase three: output checks, audit, storage -----

    private ChatReply finish(AppUser me, Prepared p, ModelResponse response) {
        PromptAssembler.Answer answer = PromptAssembler.splitSkillLine(response.text(), p.skillNames());
        GuardrailEngine.OutputResult output = GuardrailEngine.checkOutput(answer.text(), p.ctx(), p.notes());
        List<Decision> all = new ArrayList<>(p.decisions());
        all.addAll(output.decisions());

        Agent agent = agents.findById(p.agentId()).orElseThrow(() -> new NotFoundException("Agent"));
        Set<String> sourceNames = new LinkedHashSet<>();
        p.passages().forEach(x -> sourceNames.add(x.sourceName() + " / " + x.documentTitle()));
        auditDecisions(agent, p.requestId(), all, null, null, me);
        audit.record(agent.getId(), agent.getName(), "Answered a question" + (p.passages().isEmpty() ? ""
                        : " using " + p.passages().size() + (p.passages().size() == 1 ? " passage" : " passages")),
                AuditOutcome.ALLOWED, null, Map.of("requestId", p.requestId().toString(), "inputTokens", response.inputTokens(), "outputTokens", response.outputTokens()));

        List<TraceItem> trace = new ArrayList<>(trace(all));
        if (answer.skillName() != null) {
            trace.add(new TraceItem(TraceItem.Type.SKILL, answer.skillName()));
        }
        sourceNames.forEach(s -> trace.add(new TraceItem(TraceItem.Type.SOURCE, s)));
        if (p.conversationId() != null) {
            Conversation c = conversations.findById(p.conversationId()).orElse(null);
            store(c, null, output.text(), trace);
        }
        return new ChatReply(p.conversationId(), Status.ANSWERED, output.text(), trace, null);
    }

    // ----- helpers -----

    private static Phase done(ChatReply reply) {
        return new Phase(reply, null);
    }

    private AgentGuardContext context(AgentGuardrail g) {
        List<RuleSpec> specs = new ArrayList<>();
        rules.findAllByOrderByCreatedAtAsc().stream().filter(r -> r.appliesTo(g.getAgentId())).forEach(r ->
                CatchPattern.parse(r.getCatches()).ifPresent(p -> specs.add(new RuleSpec(r.getId(), r.getName(), p, r.getAction(), r.getMessage()))));
        return new AgentGuardContext(g.getPersonalData(), g.getSpecialData(), g.getApprovals(), g.getMaxRecords(), g.isHiddenInstructionProtection(), specs);
    }

    private Conversation conversation(AppUser me, Agent agent, AgentGuardrail g, UUID conversationId) {
        if (conversationId != null) {
            Conversation c = conversations.findById(conversationId).orElseThrow(() -> new NotFoundException("Conversation"));
            boolean mine = c.getStartedBy() != null && c.getStartedBy().getId().equals(me.getId());
            if (!c.getAgentId().equals(agent.getId()) || c.getChannel() != ConversationChannel.TEST || !(mine || me.isAdmin())) {
                throw new AccessDeniedException("Not your conversation");
            }
            return c;
        }
        if (g.getRetention() == Retention.NONE) {
            return null; // "Do not store": nothing is persisted and there is no memory between messages
        }
        Instant now = Instant.now();
        Instant expires = g.getRetention() == Retention.UNLIMITED ? null : now.plus(Duration.ofDays(g.getRetention().days()));
        return conversations.save(new Conversation(agent.getId(), ConversationChannel.TEST, me, now, expires));
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

    /** One audit entry per decision. A Needs-approval decision also creates the pending action. Returns its id, if any. */
    private UUID auditDecisions(Agent agent, UUID requestId, List<Decision> decisions, String pendingDescription, Conversation conversation, AppUser me) {
        UUID pendingId = null;
        for (Decision d : decisions) {
            String event = d.ruleName() == null ? d.detail() : d.detail().replaceFirst("^Rule", "Rule “" + d.ruleName() + "”");
            AuditEntry entry = audit.record(agent.getId(), agent.getName(), event, d.outcome(), d.ruleId(),
                    Map.of("requestId", requestId.toString(), "category", d.kind().name()));
            if (d.outcome() == AuditOutcome.NEEDS_APPROVAL && pendingId == null && pendingDescription != null) {
                PendingAction action = pendingActions.save(new PendingAction(agent.getId(), conversation == null ? null : conversation.getId(),
                        d.ruleId(), pendingDescription, me, entry.getId()));
                pendingId = action.getId();
            }
        }
        return pendingId;
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
