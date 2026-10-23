package com.agenticpilot.skill;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.runtime.SkillRunService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test cases for skills: a sample input and checks on the answer. Running them uses the model like "Try it", so the guardrails
 * apply. A failing or missing result never blocks an approval; the reviewer sees it and decides.
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class SkillTestService {

    public static final int MAX_CASES = 10;
    public static final int MAX_CHECKS = 3;
    public static final int NAME_MAX = 80;

    /** A case with its latest result for the version being looked at (null when never run). */
    public record CaseView(SkillTestCase testCase, SkillTestResult latest) {
    }

    private final SkillRepository skills;
    private final SkillVersionRepository skillVersions;
    private final SkillTestCaseRepository cases;
    private final SkillTestResultRepository results;
    private final SkillRunService runner;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public SkillTestService(SkillRepository skills, SkillVersionRepository skillVersions, SkillTestCaseRepository cases,
                            SkillTestResultRepository results, SkillRunService runner, CurrentUser currentUser, AuditService audit) {
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.cases = cases;
        this.results = results;
        this.runner = runner;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** The cases of a skill with their latest result for the given version number (the open draft's, or the live one). */
    @Transactional(readOnly = true)
    public List<CaseView> cases(UUID skillId, int versionNumber) {
        Map<UUID, SkillTestResult> latest = new HashMap<>();
        results.findBySkillIdAndVersionNumberOrderByRunAtDesc(skillId, versionNumber).forEach(r -> latest.putIfAbsent(r.getCaseId(), r));
        List<CaseView> out = new ArrayList<>();
        for (SkillTestCase c : cases.findBySkillIdOrderByCreatedAtAsc(skillId)) {
            out.add(new CaseView(c, latest.get(c.getId())));
        }
        return out;
    }

    /** The version number a run tests: the open draft, else the live version. */
    @Transactional(readOnly = true)
    public int currentVersionNumber(UUID skillId, boolean draft) {
        Skill skill = skill(skillId);
        if (draft) {
            return skillVersions.findByItemIdAndOpenFlag(skillId, 1).map(v -> v.getVersionNumber()).orElse(skill.getLiveVersion());
        }
        return skill.getLiveVersion();
    }

    @Transactional
    public SkillTestCase addCase(UUID skillId, String name, String input, List<SkillCheck> checks) {
        Skill skill = skill(skillId);
        currentUser.requireEdit(skill.getCreatedBy());
        if (cases.countBySkillId(skillId) >= MAX_CASES) {
            throw new InvalidInputException("error.testcase.tooMany", MAX_CASES);
        }
        SkillTestCase created = cases.save(new SkillTestCase(skillId, validName(name), validInput(input), validChecks(checks), currentUser.require()));
        audit.workspace("Test case \u201c" + created.getName() + "\u201d added to skill \u201c" + skill.getName() + "\u201d", AuditOutcome.ALLOWED, null, Map.of());
        return created;
    }

    @Transactional
    public SkillTestCase updateCase(UUID caseId, String name, String input, List<SkillCheck> checks) {
        SkillTestCase c = cases.findById(caseId).orElseThrow(() -> new NotFoundException("Test case"));
        Skill skill = skill(c.getSkillId());
        currentUser.requireEdit(skill.getCreatedBy());
        c.update(validName(name), validInput(input), validChecks(checks));
        audit.workspace("Test case \u201c" + c.getName() + "\u201d of skill \u201c" + skill.getName() + "\u201d changed", AuditOutcome.ALLOWED, null, Map.of());
        return c;
    }

    @Transactional
    public void deleteCase(UUID caseId) {
        SkillTestCase c = cases.findById(caseId).orElseThrow(() -> new NotFoundException("Test case"));
        Skill skill = skill(c.getSkillId());
        currentUser.requireEdit(skill.getCreatedBy());
        cases.delete(c);
        audit.workspace("Test case \u201c" + c.getName() + "\u201d of skill \u201c" + skill.getName() + "\u201d deleted", AuditOutcome.ALLOWED, null, Map.of());
    }

    /**
     * Runs every case against the draft (draft = true) or the approved version and stores the results. Needs a model connection;
     * without it the cases show "not run". Not one transaction: each model call can take seconds.
     */
    public List<SkillTestResult> runAll(UUID skillId, boolean draft) {
        Skill skill = skill(skillId);
        currentUser.requireEdit(skill.getCreatedBy());
        int number = currentVersionNumber(skillId, draft);
        List<SkillTestResult> out = new ArrayList<>();
        for (SkillTestCase c : cases.findBySkillIdOrderByCreatedAtAsc(skillId)) {
            SkillRunService.Result run = runner.run(skillId, c.getInput(), draft);
            SkillTestResult result;
            if (!run.ok()) {
                result = new SkillTestResult(c.getId(), skillId, number, draft, TestOutcome.NOT_RUN, run.text(), Instant.now());
            } else {
                List<String> failed = new ArrayList<>();
                for (SkillCheck check : c.getChecks()) {
                    if (!check.passes(run.text())) {
                        failed.add(check.type() + ": " + check.value());
                    }
                }
                result = new SkillTestResult(c.getId(), skillId, number, draft, failed.isEmpty() ? TestOutcome.PASSED : TestOutcome.FAILED,
                        failed.isEmpty() ? "" : "Failed " + String.join("; ", failed), Instant.now());
            }
            results.deleteByCaseIdAndVersionNumber(c.getId(), number);
            out.add(results.save(result));
        }
        audit.workspace("Test cases of skill \u201c" + skill.getName() + "\u201d run" + (draft ? " (draft)" : ""), AuditOutcome.ALLOWED, null,
                Map.of("cases", out.size(), "passed", out.stream().filter(r -> r.getOutcome() == TestOutcome.PASSED).count()));
        return out;
    }

    private Skill skill(UUID id) {
        return skills.findById(id).orElseThrow(() -> new NotFoundException("Skill"));
    }

    private static String validName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new InvalidInputException("error.nameRequired");
        }
        if (n.length() > NAME_MAX) {
            throw new InvalidInputException("error.tooLong", NAME_MAX);
        }
        return n;
    }

    private static String validInput(String input) {
        String i = input == null ? "" : input.strip();
        if (i.isEmpty()) {
            throw new InvalidInputException("error.chat.empty");
        }
        if (i.length() > SkillRunService.MAX_INPUT_CHARS) {
            throw new InvalidInputException("error.tooLong", SkillRunService.MAX_INPUT_CHARS);
        }
        return i;
    }

    private static List<SkillCheck> validChecks(List<SkillCheck> checks) {
        if (checks == null || checks.isEmpty() || checks.size() > MAX_CHECKS) {
            throw new InvalidInputException("error.testcase.checks", MAX_CHECKS);
        }
        List<SkillCheck> out = new ArrayList<>();
        for (SkillCheck c : checks) {
            if (c == null || !c.isValid()) {
                throw new InvalidInputException("error.testcase.checkInvalid");
            }
            out.add(new SkillCheck(c.type(), c.value().strip()));
        }
        return out;
    }
}
