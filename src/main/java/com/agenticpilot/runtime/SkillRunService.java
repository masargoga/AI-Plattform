package com.agenticpilot.runtime;

import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
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
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillVersionRepository;
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
    private final SkillVersionRepository skillVersions;
    private final CurrentUser currentUser;
    private final ModelGateway gateway;
    private final WorkspacePolicyService policies;
    private final AuditService audit;

    public SkillRunService(SkillRepository skills, SkillVersionRepository skillVersions, CurrentUser currentUser, ModelGateway gateway,
                           WorkspacePolicyService policies, AuditService audit) {
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.currentUser = currentUser;
        this.gateway = gateway;
        this.policies = policies;
        this.audit = audit;
    }

    /** Tries the approved version of the skill. */
    public Result run(UUID skillId, String input) {
        return run(skillId, input, false);
    }

    /** With draft = true the open draft is tried instead (only by the skill's owner or an Admin). */
    public Result run(UUID skillId, String input, boolean draft) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty()) {
            throw new InvalidInputException("error.chat.empty");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            throw new InvalidInputException("error.tooLong", MAX_INPUT_CHARS);
        }
        Skill skill = skills.findById(skillId).orElseThrow(() -> new NotFoundException("Skill"));
        SkillService.Content content = SkillService.contentOf(skill);
        if (draft) {
            currentUser.requireEdit(skill.getCreatedBy());
            content = skillVersions.findByItemIdAndOpenFlag(skillId, 1)
                    .map(v -> tools.jackson.databind.json.JsonMapper.builder().build().readValue(v.getSnapshotJson(), SkillService.Content.class))
                    .orElse(content);
        } else if (!skill.isUsable()) {
            return new Result(false, "This skill is not approved yet. Try the draft, or ask a second Admin to approve it.");
        }
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
        String system = "You run a single skill and answer only with its result.\n\n## Skill: " + content.name() + "\n"
                + (content.triggerText().isBlank() ? "" : "Use it when: " + content.triggerText() + "\n")
                + "Instructions:\n" + content.instructions() + "\nAnswer format: " + content.outputFormat().name().toLowerCase().replace('_', ' ') + "\n";
        try {
            String answer = gateway.generate(ModelTier.BALANCED, region, system, List.of(ChatMessage.user(masked))).text();
            String out = GuardrailEngine.checkOutput(answer, ctx, List.of()).text();
            audit.workspace("Skill “" + content.name() + "” tried" + (draft ? " (draft)" : ""), AuditOutcome.ALLOWED, null, Map.of("skillId", skillId.toString()));
            return new Result(true, out);
        } catch (ModelException e) {
            return new Result(false, "The model could not answer right now. Please try again in a moment.");
        }
    }
}
