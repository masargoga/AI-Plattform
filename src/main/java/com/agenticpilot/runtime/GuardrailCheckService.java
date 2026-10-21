package com.agenticpilot.runtime;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.AgentGuardContext;
import com.agenticpilot.guardrail.GuardrailReader;
import com.agenticpilot.guardrail.Decision;
import com.agenticpilot.guardrail.GuardrailEngine;
import com.agenticpilot.guardrail.InputResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guardrail check for text that comes from outside, typically from an external agent. It runs the same engine as the chat.
 * No model is called. Callers are authenticated before they get here (API key).
 */
@Service
public class GuardrailCheckService {

    public static final int MAX_TEXT_CHARS = 20_000;

    public enum Direction { INPUT, OUTPUT }

    public enum Verdict { ALLOW, MASK, BLOCK, NEEDS_APPROVAL }

    /** What the caller should do. For BLOCK and NEEDS_APPROVAL the text is empty and message is what to say instead. */
    public record Result(UUID requestId, Verdict verdict, String text, String message, List<Decision> decisions, List<String> notes, UUID pendingActionId) {
    }

    private final AgentRepository agents;
    private final GuardrailReader guardrails;
    private final GuardrailContextFactory contexts;
    private final GuardrailRecorder recorder;
    private final AuditService audit;

    public GuardrailCheckService(AgentRepository agents, GuardrailReader guardrails, GuardrailContextFactory contexts,
                                 GuardrailRecorder recorder, AuditService audit) {
        this.agents = agents;
        this.guardrails = guardrails;
        this.contexts = contexts;
        this.recorder = recorder;
        this.audit = audit;
    }

    @Transactional
    public Result check(String actor, UUID agentId, String text, Direction direction) {
        if (text == null || text.isBlank()) {
            throw new InvalidInputException("error.chat.empty");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new InvalidInputException("error.tooLong", MAX_TEXT_CHARS);
        }
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
        AgentGuardContext ctx = contexts.build(guardrails.get(agentId));
        UUID requestId = UUID.randomUUID();

        if (direction == Direction.OUTPUT) {
            GuardrailEngine.OutputResult out = GuardrailEngine.checkOutput(text, ctx, List.of());
            Verdict verdict = out.decisions().isEmpty() ? Verdict.ALLOW : Verdict.MASK;
            return finish(actor, agent, requestId, verdict, out.text(), "", out.decisions(), List.of(), null, null);
        }
        InputResult input = GuardrailEngine.checkInput(text, ctx);
        if (input instanceof InputResult.Stop stop) {
            boolean needsApproval = stop.outcome() == AuditOutcome.NEEDS_APPROVAL;
            return finish(actor, agent, requestId, needsApproval ? Verdict.NEEDS_APPROVAL : Verdict.BLOCK, "", stop.reply(), stop.decisions(), List.of(),
                    needsApproval ? stop.pendingDescription() : null, null);
        }
        InputResult.Proceed proceed = (InputResult.Proceed) input;
        boolean changed = !proceed.text().equals(text) || proceed.decisions().stream().anyMatch(d -> d.outcome() == AuditOutcome.MASKED);
        return finish(actor, agent, requestId, changed ? Verdict.MASK : Verdict.ALLOW, proceed.text(), "", proceed.decisions(), proceed.notes(), null, null);
    }

    private Result finish(String actor, Agent agent, UUID requestId, Verdict verdict, String text, String message, List<Decision> decisions,
                          List<String> notes, String pendingDescription, UUID conversationId) {
        UUID pendingId = recorder.record(actor, agent.getId(), agent.getName(), requestId, decisions, pendingDescription, conversationId, null);
        if (decisions.isEmpty()) {
            audit.recordAs(actor, agent.getId(), agent.getName(), "Guardrail check: allowed", AuditOutcome.ALLOWED, null, Map.of("requestId", requestId.toString()));
        }
        return new Result(requestId, verdict, text, message, new ArrayList<>(decisions), notes, pendingId);
    }
}
