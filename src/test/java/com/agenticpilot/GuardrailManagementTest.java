package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.audit.AuditEntry;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.compliance.ComplianceService;
import com.agenticpilot.compliance.RecordStatus;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.Control;
import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.CustomRuleService.RuleFields;
import com.agenticpilot.guardrail.PersonalDataMode;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.RuleScope;
import com.agenticpilot.guardrail.VersionStatus;
import com.agenticpilot.guardrail.WorkspacePolicy;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.support.AsUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GuardrailManagementTest {

    @Autowired AgentService agents;
    @Autowired AgentGuardrailService guardrails;
    @Autowired CustomRuleService rules;
    @Autowired WorkspacePolicyService policies;
    @Autowired AuditService audit;
    @Autowired ComplianceService compliance;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    private static String unique(String prefix) {
        return prefix + " " + System.nanoTime();
    }

    private static RuleFields fields(String name, RuleScope scope, Set<UUID> agentIds) {
        return new RuleFields(name, "lawsuit, sue", RuleAction.BLOCK, "No legal advice.", scope, agentIds, "I will sue");
    }

    private Agent agentOf(Runnable signIn) {
        signIn.run();
        return agents.create(unique("Agent"), "Answers questions.", List.of(), List.of());
    }

    private long auditCount() {
        return audit.latest(null, AuditService.MAX_ROWS).size() < AuditService.MAX_ROWS
                ? audit.latest(null, AuditService.MAX_ROWS).size()
                : jdbc.queryForObject("select count(*) from audit_entry", Long.class);
    }

    // ----- four-eyes -----

    @Test
    void newRuleIsNotLiveUntilADifferentAdminApproves() {
        AsUser.admin();
        Agent agent = agents.create(unique("A"), "j", List.of(), List.of());
        CustomRule rule = rules.propose(null, fields(unique("Rule"), RuleScope.ALL, Set.of()), "Needed for legal reasons");
        assertThat(rule.getLiveVersion()).isZero();
        assertThat(rule.isLive()).isFalse();
        assertThat(rule.appliesTo(agent.getId())).isFalse();
        assertThat(rules.pending(rule.getId())).isPresent();

        assertThatThrownBy(() -> rules.approve(rule.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(rules.get(rule.getId()).getLiveVersion()).isZero();

        AsUser.admin2();
        rules.approve(rule.getId());
        CustomRule live = rules.get(rule.getId());
        assertThat(live.getLiveVersion()).isEqualTo(1);
        assertThat(live.appliesTo(agent.getId())).isTrue();
        assertThat(rules.pending(rule.getId())).isEmpty();
        var history = rules.history(rule.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo(VersionStatus.APPROVED);
        assertThat(history.get(0).getAuthor().getUsername()).isEqualTo("admin");
        assertThat(history.get(0).getApprover().getUsername()).isEqualTo("admin2");
    }

    @Test
    void changeKeepsLiveVersionActiveUntilApproved() {
        AsUser.admin();
        CustomRule rule = rules.propose(null, fields(unique("Live"), RuleScope.ALL, Set.of()), "first");
        AsUser.admin2();
        rules.approve(rule.getId());

        RuleFields changed = new RuleFields(rule.getName(), "lawsuit, sue, attorney", RuleAction.BLOCK, "No.", RuleScope.ALL, Set.of(), "");
        rules.propose(rule.getId(), changed, "Added attorney");
        CustomRule current = rules.get(rule.getId());
        assertThat(current.getCatches()).isEqualTo("lawsuit, sue");
        assertThat(current.getLiveVersion()).isEqualTo(1);

        assertThatThrownBy(() -> rules.propose(rule.getId(), changed, "again")).isInstanceOf(InvalidInputException.class);

        AsUser.admin();
        rules.approve(rule.getId());
        assertThat(rules.get(rule.getId()).getCatches()).isEqualTo("lawsuit, sue, attorney");
        assertThat(rules.get(rule.getId()).getLiveVersion()).isEqualTo(2);
        assertThat(rules.history(rule.getId())).hasSize(2);
    }

    @Test
    void databaseAlsoRefusesSelfApproval() {
        AsUser.admin();
        CustomRule rule = rules.propose(null, fields(unique("Db"), RuleScope.ALL, Set.of()), "n");
        UUID adminId = jdbc.queryForObject("select id from app_user where username = 'admin'", UUID.class);
        assertThatThrownBy(() -> jdbc.update("update rule_version set approver_id = ?, status = 'APPROVED', pending_flag = null where rule_id = ?",
                adminId, rule.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOnePendingVersionPerRuleInTheDatabase() {
        AsUser.admin();
        CustomRule rule = rules.propose(null, fields(unique("One"), RuleScope.ALL, Set.of()), "n");
        UUID adminId = jdbc.queryForObject("select id from app_user where username = 'admin'", UUID.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into rule_version (id, rule_id, version_number, snapshot, author_id, note, status, pending_flag, created_at, updated_at)
                values (random_uuid(), ?, 2, '{}', ?, 'x', 'PENDING', 1, current_timestamp, current_timestamp)""", rule.getId(), adminId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectingOrWithdrawingANewRuleRemovesIt() {
        AsUser.admin();
        CustomRule a = rules.propose(null, fields(unique("Rej"), RuleScope.ALL, Set.of()), "n");
        assertThatThrownBy(() -> rules.reject(a.getId())).isInstanceOf(AccessDeniedException.class);
        AsUser.admin2();
        assertThatThrownBy(() -> rules.withdraw(a.getId())).isInstanceOf(AccessDeniedException.class);
        rules.reject(a.getId());
        assertThat(rules.all()).extracting(CustomRule::getId).doesNotContain(a.getId());

        AsUser.admin();
        CustomRule b = rules.propose(null, fields(unique("Wd"), RuleScope.ALL, Set.of()), "n");
        rules.withdraw(b.getId());
        assertThat(rules.all()).extracting(CustomRule::getId).doesNotContain(b.getId());
    }

    @Test
    void rejectedChangeToLiveRuleKeepsTheLiveVersionAndShowsInHistory() {
        AsUser.admin();
        CustomRule rule = rules.propose(null, fields(unique("Keep"), RuleScope.ALL, Set.of()), "n");
        AsUser.admin2();
        rules.approve(rule.getId());
        rules.propose(rule.getId(), new RuleFields(rule.getName(), "other", RuleAction.MASK, "", RuleScope.ALL, Set.of(), ""), "change");
        AsUser.admin();
        rules.reject(rule.getId());
        assertThat(rules.get(rule.getId()).getCatches()).isEqualTo("lawsuit, sue");
        assertThat(rules.history(rule.getId())).extracting(v -> v.getStatus()).containsExactly(VersionStatus.REJECTED, VersionStatus.APPROVED);
    }

    // ----- who may propose -----

    @Test
    void ownerMayProposeOnlyForOwnAgentsAndNeverForAllAgents() {
        Agent own = agentOf(AsUser::owner);
        Agent others = agentOf(AsUser::admin);

        AsUser.owner();
        assertThatThrownBy(() -> rules.propose(null, fields(unique("All"), RuleScope.ALL, Set.of()), "n")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rules.propose(null, fields(unique("Other"), RuleScope.SELECTED, Set.of(others.getId())), "n"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rules.propose(null, fields(unique("None"), RuleScope.SELECTED, Set.of()), "n")).isInstanceOf(InvalidInputException.class);
        CustomRule rule = rules.propose(null, fields(unique("Mine"), RuleScope.SELECTED, Set.of(own.getId())), "n");
        assertThat(rule.getCreatedBy().getUsername()).isEqualTo("owner");

        // an owner cannot approve, turn on or off, or delete
        assertThatThrownBy(() -> rules.approve(rule.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rules.setEnabled(rule.getId(), false)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rules.delete(rule.getId())).isInstanceOf(AccessDeniedException.class);

        // any Admin can approve an owner's change, and then the owner can propose a change again
        AsUser.admin();
        rules.approve(rule.getId());
        AsUser.owner();
        assertThat(rules.canEdit(rules.get(rule.getId()))).isTrue();
        AsUser.admin();
        CustomRule adminRule = rules.propose(null, fields(unique("Admins"), RuleScope.SELECTED, Set.of(own.getId(), others.getId())), "n");
        AsUser.owner();
        assertThat(rules.canEdit(rules.get(adminRule.getId()))).isFalse();
    }

    @Test
    void addingAnAgentToARuleIsAChangeThatNeedsApproval() {
        Agent a1 = agentOf(AsUser::owner);
        Agent a2 = agentOf(AsUser::owner);
        AsUser.owner();
        CustomRule rule = rules.propose(null, fields(unique("Assign"), RuleScope.SELECTED, Set.of(a1.getId())), "n");
        AsUser.admin();
        rules.approve(rule.getId());

        AsUser.owner();
        rules.proposeAgentAssignment(rule.getId(), a2.getId(), true);
        assertThat(rules.get(rule.getId()).getAgentIds()).containsExactly(a1.getId());
        assertThat(rules.pending(rule.getId())).isPresent();
        AsUser.admin();
        rules.approve(rule.getId());
        assertThat(rules.get(rule.getId()).getAgentIds()).containsExactlyInAnyOrder(a1.getId(), a2.getId());
        assertThat(rules.forAgent(a2.getId())).extracting(CustomRule::getId).contains(rule.getId());
    }

    @Test
    void adminCanTurnRulesOffAndDeleteThem() {
        AsUser.admin();
        CustomRule rule = rules.propose(null, fields(unique("Toggle"), RuleScope.ALL, Set.of()), "n");
        AsUser.admin2();
        rules.approve(rule.getId());
        rules.setEnabled(rule.getId(), false);
        assertThat(rules.get(rule.getId()).isLive()).isFalse();
        rules.delete(rule.getId());
        assertThat(rules.all()).extracting(CustomRule::getId).doesNotContain(rule.getId());
    }

    @Test
    void invalidRuleInputIsRefused() {
        AsUser.admin();
        assertThatThrownBy(() -> rules.propose(null, new RuleFields(" ", "x", RuleAction.BLOCK, "", RuleScope.ALL, Set.of(), ""), "n"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> rules.propose(null, new RuleFields("n", "/[/", RuleAction.BLOCK, "", RuleScope.ALL, Set.of(), ""), "n"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> rules.propose(null, fields("n", RuleScope.ALL, Set.of()), "  ")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> rules.propose(null, fields("x".repeat(49), RuleScope.ALL, Set.of()), "n")).isInstanceOf(InvalidInputException.class);
    }

    // ----- built-in guardrails and policies -----

    @Test
    void newAgentsGetRecommendedGuardrailsAndOnlyOwnerOrAdminChangeThem() {
        Agent agent = agentOf(AsUser::admin);
        AgentGuardrail g = guardrails.get(agent.getId());
        assertThat(g.getPersonalData()).isEqualTo(PersonalDataMode.MASK);
        assertThat(g.getApprovals()).containsExactly(ApprovalAction.EMAIL);
        assertThat(guardrails.score(agent.getId()).met()).isEqualTo(7);

        AsUser.owner();
        assertThatThrownBy(() -> guardrails.setRetention(agent.getId(), Retention.DAYS_30)).isInstanceOf(AccessDeniedException.class);
        AsUser.admin2();
        guardrails.setRetention(agent.getId(), Retention.UNLIMITED);
        assertThat(guardrails.score(agent.getId()).gaps()).containsExactly(Control.RETENTION);
        guardrails.fix(agent.getId(), Control.RETENTION);
        assertThat(guardrails.get(agent.getId()).getRetention()).isEqualTo(Retention.DAYS_90);
        assertThat(guardrails.score(agent.getId()).met()).isEqualTo(7);
    }

    @Test
    void workspacePolicyIsAdminOnlyKeepsHistoryAndAdjustsAgents() {
        AsUser.admin();
        WorkspacePolicy before = policies.current();
        boolean pii = before.isPersonalDataOn(), special = before.isSpecialDataOn(), eu = before.isEuOnlyOn();
        Retention retention = before.getDefaultRetention();
        int revision = before.getRevision();
        try {
            Agent agent = agentOf(AsUser::owner);
            AsUser.owner();
            assertThatThrownBy(() -> policies.update(pii, special, true, retention)).isInstanceOf(AccessDeniedException.class);

            AsUser.admin();
            policies.update(pii, special, false, retention);
            guardrails.setRegion(agent.getId(), Region.ANY);
            guardrails.setPersonalData(agent.getId(), PersonalDataMode.MASK);

            var update = policies.update(true, special, true, retention);
            assertThat(update.adjustedAgents()).contains(agent.getName());
            assertThat(guardrails.get(agent.getId()).getRegion()).isEqualTo(Region.EU);
            assertThat(policies.history()).hasSizeGreaterThan(revision);

            // below the policy is refused on the server
            assertThatThrownBy(() -> guardrails.setRegion(agent.getId(), Region.ANY)).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> guardrails.setPersonalData(agent.getId(), PersonalDataMode.ALLOW)).isInstanceOf(InvalidInputException.class);

            // no change, no new version
            int now = policies.current().getRevision();
            policies.update(true, special, true, retention);
            assertThat(policies.current().getRevision()).isEqualTo(now);
        } finally {
            AsUser.admin();
            policies.update(pii, special, eu, retention);
        }
    }

    // ----- audit and compliance -----

    @Test
    void everyStateChangeWritesAnAuditEntry() {
        AsUser.admin();
        long start = auditCount();
        Agent agent = agents.create(unique("Audited"), "j", List.of(), List.of());
        assertThat(auditCount()).isGreaterThan(start);
        long n = auditCount();
        guardrails.setHumanReview(agent.getId(), false);
        assertThat(auditCount()).isEqualTo(n + 1);
        agents.setStatus(agent.getId(), com.agenticpilot.agent.AgentStatus.PAUSED);
        assertThat(auditCount()).isEqualTo(n + 2);

        CustomRule rule = rules.propose(null, fields(unique("Aud"), RuleScope.ALL, Set.of()), "n");
        List<AuditEntry> latest = audit.latest(AuditOutcome.NEEDS_APPROVAL, 5);
        assertThat(latest.get(0).getEvent()).contains("proposed by");
        assertThat(latest.get(0).getRuleId()).isEqualTo(rule.getId());
        long afterPropose = auditCount();
        AsUser.admin2();
        rules.approve(rule.getId());
        rules.setEnabled(rule.getId(), false);
        rules.delete(rule.getId());
        assertThat(auditCount()).isEqualTo(afterPropose + 3);
        assertThat(audit.latest(null, 1).get(0).getEvent()).contains("deleted");
    }

    @Test
    void auditExportNeutralisesSpreadsheetFormulas() {
        assertThat(AuditService.cell("=SUM(A1)")).isEqualTo("\"'=SUM(A1)\"");
        assertThat(AuditService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        AsUser.owner();
        assertThat(audit.exportCsv(null)).startsWith("time,agent,actor,event,outcome");
    }

    @Test
    void complianceItemsCanOnlyBeCompletedByAdmins() {
        AsUser.admin();
        UUID record = UUID.randomUUID();
        jdbc.update("insert into compliance_record (id, title, owner_name, status, created_at, updated_at) values (?, 't', 'o', 'TODO', current_timestamp, current_timestamp)", record);
        int open = compliance.openItems();
        AsUser.owner();
        assertThatThrownBy(() -> compliance.completeRecord(record)).isInstanceOf(AccessDeniedException.class);
        AsUser.admin();
        compliance.completeRecord(record);
        assertThat(compliance.records()).filteredOn(r -> r.getId().equals(record)).extracting(r -> r.getStatus()).containsExactly(RecordStatus.DONE);
        assertThat(compliance.openItems()).isEqualTo(open - 1);
    }
}
