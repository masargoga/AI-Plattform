package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.compliance.ComplianceService;
import com.agenticpilot.governance.ApprovalService;
import com.agenticpilot.governance.ApprovalService.ItemType;
import com.agenticpilot.governance.GovernanceWarnings;
import com.agenticpilot.guardrail.Control;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.CustomRuleService.RuleFields;
import com.agenticpilot.guardrail.Enforcement;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.RuleScope;
import com.agenticpilot.runtime.AgentChatService;
import com.agenticpilot.runtime.ChatReply;
import com.agenticpilot.skill.CheckType;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillCheck;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillTestResult;
import com.agenticpilot.skill.SkillTestService;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.skill.TestOutcome;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.OnFailure;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.TrustLevel;
import com.agenticpilot.source.ingest.IngestionService;
import com.agenticpilot.support.Approve;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.support.FakeModelConfig;
import com.agenticpilot.support.FakeModelConfig.FakeModel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

/** Approvals screen data, data source settings, skill test cases, audit search and the enforcement labels. */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeModelConfig.class)
class GovernanceFeaturesTest {

    @Autowired SkillService skills;
    @Autowired AgentService agents;
    @Autowired ApprovalService approvals;
    @Autowired GovernanceWarnings warnings;
    @Autowired DataSourceService sources;
    @Autowired IngestionService ingestion;
    @Autowired AgentChatService chat;
    @Autowired SkillTestService tests;
    @Autowired CustomRuleService rules;
    @Autowired AuditService audit;
    @Autowired ComplianceService compliance;
    @Autowired FakeModel model;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        model.reset();
    }

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    private static String unique(String p) {
        return p + " " + System.nanoTime();
    }

    private static SkillService.Content content(String name, String instructions) {
        return new SkillService.Content(name, SkillType.INSTRUCTION, "d", "t", instructions, Set.of(), OutputFormat.PLAIN_TEXT);
    }

    // ----- approvals -----

    @Test
    void theApprovalsListCoversRulesSkillsAndAgentsAndExplainsEachChange() {
        AsUser.admin2();
        int before = approvals.pendingCount();
        int openBefore = compliance.openItems();
        AsUser.owner();
        Skill fresh = skills.create(content(unique("Fresh"), "Do the new thing"));
        skills.submit(fresh.getId(), "Please add");

        Skill live = skills.create(content(unique("Live"), "old rules"));
        Approve.skill(skills, live.getId());
        AsUser.owner();
        skills.saveDraft(live.getId(), content(skills.get(live.getId()).getName(), "new rules"));
        skills.submit(live.getId(), "Tighten");

        AsUser.admin();
        rules.propose(null, new RuleFields(unique("Rule"), "lawsuit", RuleAction.BLOCK, "No.", RuleScope.ALL, Set.of(), "I will sue"), "legal");

        AsUser.admin2();
        List<ApprovalService.ReviewItem> pending = approvals.pending();
        assertThat(approvals.pendingCount()).isEqualTo(before + 3);
        assertThat(compliance.openItems()).isEqualTo(openBefore + 3);
        assertThat(pending).extracting(ApprovalService.ReviewItem::type).contains(ItemType.RULE, ItemType.SKILL);

        var freshItem = pending.stream().filter(i -> i.itemId().equals(fresh.getId())).findFirst().orElseThrow();
        assertThat(freshItem.isNew()).isTrue();
        assertThat(freshItem.mine()).isFalse();
        assertThat(approvals.detail(ItemType.SKILL, freshItem.versionId()).changes()).extracting(ApprovalService.Change::field).contains("name", "instructions");

        var changed = pending.stream().filter(i -> i.itemId().equals(live.getId())).findFirst().orElseThrow();
        assertThat(changed.isNew()).isFalse();
        var changes = approvals.detail(ItemType.SKILL, changed.versionId()).changes();
        assertThat(changes).extracting(ApprovalService.Change::field).containsExactly("instructions");
        assertThat(changes.get(0).before()).isEqualTo("old rules");
        assertThat(changes.get(0).after()).isEqualTo("new rules");

        assertThatThrownBy(() -> approvals.reject(ItemType.SKILL, changed.versionId(), " ")).isInstanceOf(InvalidInputException.class);
        approvals.approve(ItemType.SKILL, changed.versionId(), "good");
        approvals.reject(ItemType.SKILL, freshItem.versionId(), "Not needed");
        assertThat(skills.get(live.getId()).getInstructions()).isEqualTo("new rules");
        assertThat(approvals.pendingCount()).isEqualTo(before + 1);
    }

    @Test
    void anOwnChangeIsMarkedAsYoursAndCannotBeDecidedByTheAuthor() {
        AsUser.admin();
        Skill s = skills.create(content(unique("Mine"), "x"));
        skills.submit(s.getId(), "n");
        var item = approvals.pending().stream().filter(i -> i.itemId().equals(s.getId())).findFirst().orElseThrow();
        assertThat(item.mine()).isTrue();
        assertThatThrownBy(() -> approvals.approve(ItemType.SKILL, item.versionId(), null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void historyCompareShowsWhatChangedAgainstThePreviousVersion() {
        AsUser.owner();
        Skill s = skills.create(content(unique("Hist"), "one"));
        Approve.skill(skills, s.getId());
        AsUser.owner();
        skills.saveDraft(s.getId(), content(skills.get(s.getId()).getName(), "two"));
        Approve.skill(skills, s.getId());
        var versions = skills.history(s.getId());
        assertThat(versions).hasSize(2);
        var latest = approvals.compare(ItemType.SKILL, s.getId(), versions.get(0).getId());
        assertThat(latest).extracting(ApprovalService.Change::field).containsExactly("instructions");
        var first = approvals.compare(ItemType.SKILL, s.getId(), versions.get(1).getId());
        assertThat(first).extracting(ApprovalService.Change::field).contains("name", "instructions");
    }

    // ----- data sources: settings, warnings, behaviour on failure -----

    private DataSource upload(String word) {
        AsUser.owner();
        DataSource s = sources.create(unique("Docs"), SourceKind.UPLOAD, "files");
        ingestion.addFile(s.getId(), "facts.txt", ("The " + word + " is documented in this file. " + word + " matters a lot.").getBytes(StandardCharsets.UTF_8));
        return s;
    }

    @Test
    void sourceSettingsAreCheckedAndAudited() {
        DataSource s = upload("zebra" + System.nanoTime());
        assertThat(s.getTrustLevel()).isEqualTo(TrustLevel.INFORMATIONAL);
        assertThat(s.getOnFailure()).isEqualTo(OnFailure.USE_LAST_COPY);
        assertThat(s.getMaxAgeDays()).isEqualTo(5);

        AsUser.owner();
        var settings = new DataSourceService.Settings("Legal team", TrustLevel.AUTHORITATIVE, OnFailure.STOP_AND_REPORT, 14, LocalDate.now().minusDays(1), "Policy v3, 1 May");
        DataSource updated = sources.updateSettings(s.getId(), settings);
        assertThat(updated.getTrustLevel()).isEqualTo(TrustLevel.AUTHORITATIVE);
        assertThat(updated.isReviewOverdue(LocalDate.now())).isTrue();
        assertThat(updated.getMaxAgeDays()).isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where event like 'Settings of data source%' and details like '%AUTHORITATIVE%'", Integer.class)).isPositive();

        assertThatThrownBy(() -> sources.updateSettings(s.getId(), new DataSourceService.Settings("x", TrustLevel.AUTHORITATIVE, OnFailure.USE_LAST_COPY, 0, null, "")))
                .isInstanceOf(InvalidInputException.class);

        AsUser.admin();
        DataSource adminSource = sources.create(unique("AdminDocs"), SourceKind.UPLOAD, "files");
        AsUser.owner();
        assertThatThrownBy(() -> sources.updateSettings(adminSource.getId(), settings)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void complianceRelevantSkillsWarnWhenNoSourceIsAuthoritative() {
        AsUser.owner();
        Skill relevant = skills.create(new SkillService.Content(unique("Rules"), SkillType.INSTRUCTION, "d", "t", "i", Set.of(), OutputFormat.PLAIN_TEXT, true));
        Approve.skill(skills, relevant.getId());
        Skill plain = skills.create(content(unique("Plain"), "i"));
        Approve.skill(skills, plain.getId());
        DataSource info = upload("giraffe" + System.nanoTime());

        AgentService.Content none = new AgentService.Content("A", "j", "", null, null, Set.of(relevant.getId()), Set.of());
        assertThat(warnings.forAgent(none)).containsExactly(GovernanceWarnings.NO_SOURCES);
        AgentService.Content informational = new AgentService.Content("A", "j", "", null, null, Set.of(relevant.getId()), Set.of(info.getId()));
        assertThat(warnings.forAgent(informational)).containsExactly(GovernanceWarnings.INFORMATIONAL_ONLY);
        AgentService.Content notRelevant = new AgentService.Content("A", "j", "", null, null, Set.of(plain.getId()), Set.of());
        assertThat(warnings.forAgent(notRelevant)).isEmpty();

        AsUser.owner();
        sources.updateSettings(info.getId(), new DataSourceService.Settings("", TrustLevel.AUTHORITATIVE, OnFailure.USE_LAST_COPY, 5, null, ""));
        assertThat(warnings.forAgent(informational)).isEmpty();
    }

    @Test
    void stopAndReportLeavesAFailedOrOldSourceOutAndSaysSo() {
        String word = "okapi" + System.nanoTime();
        DataSource s = upload(word);
        AsUser.owner();
        Agent agent = agents.create(unique("Reader"), "j", List.of(), List.of(s.getId()));
        Approve.agent(agents, agent.getId());
        AsUser.owner();

        // default: the last copy is used even when the source is in error
        jdbc.update("update data_source set status = 'ERROR' where id = ?", s.getId());
        ChatReply copy = chat.send(agent.getId(), null, "What is " + word + "?");
        assertThat(model.last().system()).contains(word).doesNotContain("Unavailable data sources");
        assertThat(copy.trace()).extracting(ChatReply.TraceItem::type).contains(ChatReply.TraceItem.Type.SOURCE);

        sources.updateSettings(s.getId(), new DataSourceService.Settings("", TrustLevel.INFORMATIONAL, OnFailure.STOP_AND_REPORT, 5, null, ""));
        ChatReply stopped = chat.send(agent.getId(), null, "What is " + word + "?");
        assertThat(model.last().system()).contains("Unavailable data sources").contains(s.getName()).doesNotContain("is documented in this file");
        assertThat(stopped.trace()).extracting(ChatReply.TraceItem::text).anyMatch(t -> t.startsWith("Source unavailable"));
        assertThat(stopped.trace()).extracting(ChatReply.TraceItem::type).doesNotContain(ChatReply.TraceItem.Type.SOURCE);

        // healthy again, but older than the maximum age
        jdbc.update("update data_source set status = 'SYNCED', last_synced = ? where id = ?", Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), s.getId());
        chat.send(agent.getId(), null, "What is " + word + "?");
        assertThat(model.last().system()).contains("Unavailable data sources");

        jdbc.update("update data_source set last_synced = ? where id = ?", Timestamp.from(Instant.now()), s.getId());
        chat.send(agent.getId(), null, "What is " + word + "?");
        assertThat(model.last().system()).doesNotContain("Unavailable data sources").contains(word);
    }

    // ----- skill test cases -----

    private UUID approvedSkill() {
        AsUser.owner();
        Skill s = skills.create(content(unique("Tested"), "Answer briefly"));
        Approve.skill(skills, s.getId());
        AsUser.owner();
        return s.getId();
    }

    @Test
    void testCasesCheckTheAnswerAndKeepResultsPerVersion() {
        UUID id = approvedSkill();
        tests.addCase(id, "Greets", "Say hello to Ana", List.of(new SkillCheck(CheckType.CONTAINS, "hello"), new SkillCheck(CheckType.NOT_CONTAINS, "goodbye"),
                new SkillCheck(CheckType.MATCHES, "/h.llo/")));

        model.answer = r -> "Hello Ana, nice to meet you";
        List<SkillTestResult> ok = tests.runAll(id, false);
        assertThat(ok).extracting(SkillTestResult::getOutcome).containsExactly(TestOutcome.PASSED);

        model.answer = r -> "Goodbye Ana";
        List<SkillTestResult> failed = tests.runAll(id, false);
        assertThat(failed.get(0).getOutcome()).isEqualTo(TestOutcome.FAILED);
        assertThat(failed.get(0).getDetail()).contains("CONTAINS: hello").contains("NOT_CONTAINS: goodbye");
        assertThat(tests.cases(id, 1).get(0).latest().getOutcome()).isEqualTo(TestOutcome.FAILED);

        model.failing = true;
        assertThat(tests.runAll(id, false).get(0).getOutcome()).isEqualTo(TestOutcome.NOT_RUN);

        // a draft is tested under its own version number
        model.failing = false;
        model.answer = r -> "Hello again";
        skills.saveDraft(id, content(skills.get(id).getName(), "Answer very briefly"));
        assertThat(tests.currentVersionNumber(id, true)).isEqualTo(2);
        tests.runAll(id, true);
        assertThat(tests.cases(id, 2).get(0).latest().getOutcome()).isEqualTo(TestOutcome.PASSED);
        assertThat(tests.cases(id, 1).get(0).latest().getOutcome()).isEqualTo(TestOutcome.NOT_RUN);
        assertThat(model.last().system()).contains("Answer very briefly");
    }

    @Test
    void testCasesAreValidatedAndLimitedToThoseWhoMayEditTheSkill() {
        UUID id = approvedSkill();
        var check = List.of(new SkillCheck(CheckType.CONTAINS, "x"));
        assertThatThrownBy(() -> tests.addCase(id, " ", "in", check)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> tests.addCase(id, "n", " ", check)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> tests.addCase(id, "n", "in", List.of())).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> tests.addCase(id, "n", "in", List.of(new SkillCheck(CheckType.MATCHES, "/(/")))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> tests.addCase(id, "n", "in", List.of(check.get(0), check.get(0), check.get(0), check.get(0)))).isInstanceOf(InvalidInputException.class);
        for (int i = 0; i < SkillTestService.MAX_CASES; i++) {
            tests.addCase(id, "case " + i, "in", check);
        }
        assertThatThrownBy(() -> tests.addCase(id, "one too many", "in", check)).isInstanceOf(InvalidInputException.class);

        AsUser.admin();
        Skill adminSkill = skills.create(content(unique("AdminSkill"), "x"));
        AsUser.owner();
        assertThatThrownBy(() -> tests.addCase(adminSkill.getId(), "n", "in", check)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tests.runAll(adminSkill.getId(), false)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnapprovedSkillCannotBeTriedAsApprovedAndNeedsTheDraftSwitch() {
        AsUser.owner();
        Skill s = skills.create(content(unique("Wip"), "work"));
        tests.addCase(s.getId(), "c", "hello there", List.of(new SkillCheck(CheckType.CONTAINS, "ok")));
        model.answer = r -> "ok";
        assertThat(tests.runAll(s.getId(), false).get(0).getOutcome()).isEqualTo(TestOutcome.NOT_RUN);
        assertThat(tests.runAll(s.getId(), true).get(0).getOutcome()).isEqualTo(TestOutcome.PASSED);
    }

    // ----- audit search -----

    @Test
    void theAuditLogCanBeSearchedByTextAgentOutcomeAndDates() {
        String token = "needle" + System.nanoTime();
        AsUser.owner();
        Agent agent = agents.create(unique("Audited"), "j", List.of(), List.of());
        audit.record(agent.getId(), agent.getName(), "Event about " + token, AuditOutcome.BLOCKED, null, java.util.Map.of());
        audit.workspace("Workspace event " + token + " 100% sure", AuditOutcome.ALLOWED, null, java.util.Map.of());

        var all = audit.search(new AuditService.Filter(null, null, token, null, null), 50);
        assertThat(all).hasSize(2);
        assertThat(audit.search(new AuditService.Filter(AuditOutcome.BLOCKED, null, token, null, null), 50)).hasSize(1);
        assertThat(audit.search(new AuditService.Filter(null, agent.getId(), token, null, null), 50)).hasSize(1);
        assertThat(audit.search(new AuditService.Filter(null, null, token.toUpperCase(), null, null), 50)).hasSize(2);
        assertThat(audit.search(new AuditService.Filter(null, null, agent.getName(), null, null), 50)).isNotEmpty();
        assertThat(audit.search(new AuditService.Filter(null, null, token, LocalDate.now(), LocalDate.now()), 50)).hasSize(2);
        assertThat(audit.search(new AuditService.Filter(null, null, token, LocalDate.now().plusDays(1), null), 50)).isEmpty();
        assertThat(audit.search(new AuditService.Filter(null, null, token, null, LocalDate.now().minusDays(1)), 50)).isEmpty();
        // a percent sign in the search text is not a wildcard
        assertThat(audit.search(new AuditService.Filter(null, null, "100% sure", null, null), 50)).hasSize(1);
        assertThat(audit.search(new AuditService.Filter(null, null, "%", null, null), 50)).hasSize(1);

        String csv = audit.exportCsv(new AuditService.Filter(AuditOutcome.BLOCKED, null, token, null, null));
        assertThat(csv).contains("Event about " + token).doesNotContain("Workspace event");
    }

    // ----- enforcement labels -----

    @Test
    void everyControlHasAnEnforcementLabel() {
        for (Control c : Control.values()) {
            assertThat(Enforcement.of(Enforcement.topicOf(c))).isNotNull();
        }
        for (Enforcement.Topic t : Enforcement.Topic.values()) {
            assertThat(Enforcement.of(t)).isNotNull();
        }
        assertThat(Enforcement.of(Enforcement.Topic.PURPOSE)).isEqualTo(Enforcement.MODEL);
        assertThat(Enforcement.of(Enforcement.Topic.HUMAN_REVIEW)).isEqualTo(Enforcement.RECORDED_ONLY);
        assertThat(Enforcement.of(Enforcement.Topic.PERSONAL_DATA)).isEqualTo(Enforcement.PLATFORM);
        assertThat(Enforcement.of(Enforcement.Topic.CUSTOM_RULES)).isEqualTo(Enforcement.PLATFORM);
    }
}
