package com.agenticpilot.runtime;

import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.AgentGuardContext;
import com.agenticpilot.guardrail.GuardrailEngine;
import com.agenticpilot.guardrail.InputResult;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.runtime.provider.ChatMessage;
import com.agenticpilot.runtime.provider.ModelException;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Try it" for a skill: runs the skill's instructions on a sample input with the model. The input goes through the strict
 * guardrail context first (personal data masked, special categories blocked), because a skill is not tied to an agent.
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class SkillRunService {

    public static final int MAX_INPUT_CHARS = 4000;

    /** The outcome of a trial run. */
    public record Result(boolean ok, String text) {
    }

    private final SkillRepository skills;
    private final ModelGateway gateway;
    private final WorkspacePolicyService policies;
    private final AuditService audit;

    public SkillRunService(SkillRepository skills, ModelGateway gateway, WorkspacePolicyService policies, AuditService audit) {
        this.skills = skills;
        this.gateway = gateway;
        this.policies = policies;
        this.audit = audit;
    }

    public Result run(UUID skillId, String input) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty()) {
            throw new InvalidInputException("error.chat.empty");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            throw new InvalidInputException("error.tooLong", MAX_INPUT_CHARS);
        }
        Skill skill = skills.findById(skillId).orElseThrow(() -> new NotFoundException("Skill"));
        AgentGuardContext ctx = AgentGuardContext.strict();
        InputResult checked = GuardrailEngine.checkInput(text, ctx);
        if (checked instanceof InputResult.Stop stop) {
            audit.workspace("Skill trial stopped by a guardrail", stop.outcome(), null, Map.of("skillId", skillId.toString()));
            return new Result(false, stop.reply());
        }
        String masked = ((InputResult.Proceed) checked).text();
        Region region = policies.current().isEuOnlyOn() ? Region.EU : Region.ANY;
        if (gateway.refusalFor(region).isPresent()) {
            return new Result(false, gateway.refusalFor(region).get() == ModelGateway.Refusal.EU_NOT_AVAILABLE
                    ? "A workspace rule keeps data in the EU, but no EU-approved model endpoint is configured."
                    : "No model connection is configured yet. Ask an operator to set AGENTICPILOT_MODEL_API_KEY.");
        }
        String system = "You run a single skill and answer only with its result.\n\n## Skill: " + skill.getName() + "\n"
                + (skill.getTriggerText().isBlank() ? "" : "Use it when: " + skill.getTriggerText() + "\n")
                + "Instructions:\n" + skill.getInstructions() + "\nAnswer format: " + skill.getOutputFormat().name().toLowerCase().replace('_', ' ') + "\n";
        try {
            String answer = gateway.generate(ModelTier.BALANCED, region, system, List.of(ChatMessage.user(masked))).text();
            String out = GuardrailEngine.checkOutput(answer, ctx, List.of()).text();
            audit.workspace("Skill “" + skill.getName() + "” tried", AuditOutcome.ALLOWED, null, Map.of("skillId", skillId.toString()));
            return new Result(true, out);
        } catch (ModelException e) {
            return new Result(false, "The model could not answer right now. Please try again in a moment.");
        }
    }
}
