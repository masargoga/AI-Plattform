package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.governance.ChangeStatus;
import com.agenticpilot.governance.Lifecycle;
import com.agenticpilot.runtime.AgentChatService;
import com.agenticpilot.runtime.ChatReply;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.support.Approve;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.support.FakeModelConfig;
import com.agenticpilot.support.FakeModelConfig.FakeModel;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

/** The approval lifecycle of skills and agents: versions, four-eyes, only approved versions run. */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeModelConfig.class)
class GovernanceTest {

    @Autowired SkillService skills;
    @Autowired AgentService agents;
    @Autowired AgentChatService chat;
    @Autowired FakeModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

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

    private UUID approvedSkill(String name) {
        AsUser.owner();
        Skill s = skills.create(content(name, "v1 rules"));
        Approve.skill(skills, s.getId());
        return s.getId();
    }

    // ----- skills -----

    @Test
    void aNewSkillIsADraftAndNotUsableUntilAnotherAdminApproves() {
        AsUser.owner();
        Skill s = skills.create(content(unique("New"), "Do it"));
        assertThat(s.getLifecycle()).isEqualTo(Lifecycle.DRAFT);
        assertThat(s.getLiveVersion()).isZero();
        assertThat(skills.approved()).extracting(Skill::getId).doesNotContain(s.getId());

        skills.submit(s.getId(), "First version");
        assertThat(skills.get(s.getId()).getLifecycle()).isEqualTo(Lifecycle.IN_REVIEW);
        UUID versionId = skills.openVersion(s.getId()).orElseThrow().getId();

        AsUser.owner();
        assertThatThrownBy(() -> skills.approve(versionId, null)).isInstanceOf(AccessDeniedException.class); // not an Admin
        AsUser.admin();
        AsUser.admin(); // the author is an Admin here only if the Admin submitted: the owner submitted, so test that below
        skills.approve(versionId, "ok");
        Skill live = skills.get(s.getId());
        assertThat(live.getLifecycle()).isEqualTo(Lifecycle.APPROVED);
        assertThat(live.getLiveVersion()).isEqualTo(1);
        assertThat(skills.approved()).extracting(Skill::getId).contains(s.getId());
        assertThat(skills.history(s.getId())).extracting(v -> v.getStatus()).containsExactly(ChangeStatus.APPROVED);
    }

    @Test
    void theAuthorCannotApproveTheirOwnChange() {
        AsUser.admin();
        Skill s = skills.create(content(unique("Mine"), "x"));
        skills.submit(s.getId(), "n");
        UUID versionId = skills.openVersion(s.getId()).orElseThrow().getId();
        assertThatThrownBy(() -> skills.approve(versionId, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> skills.reject(versionId, "no")).isInstanceOf(AccessDeniedException.class);
        AsUser.admin2();
        skills.approve(versionId, null);
        assertThat(skills.get(s.getId()).getLiveVersion()).isEqualTo(1);
    }

    @Test
    void theDatabaseAlsoRefusesAnApproverWhoIsTheAuthor() {
        AsUser.owner();
        Skill s = skills.create(content(unique("Db"), "x"));
        assertThatThrownBy(() -> jdbc.update("update skill_version set approver_id = author_id, status = 'APPROVED', open_flag = null where item_id = ?", s.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void editingAnApprovedSkillMakesADraftAndLeavesTheLiveVersionAlone() {
        UUID id = approvedSkill(unique("Edit"));
        AsUser.owner();
        skills.saveDraft(id, content(skills.get(id).getName(), "v2 rules"));
        assertThat(skills.get(id).getInstructions()).isEqualTo("v1 rules");
        assertThat(skills.editable(id).instructions()).isEqualTo("v2 rules");
        assertThat(skills.openVersion(id).orElseThrow().getVersionNumber()).isEqualTo(2);

        skills.submit(id, "Better rules");
        // a pending change cannot be edited
        assertThatThrownBy(() -> skills.saveDraft(id, content("x", "y"))).isInstanceOf(InvalidInputException.class).hasMessage("error.lifecycle.pendingLocked");
        assertThat(skills.get(id).getInstructions()).isEqualTo("v1 rules");

        AsUser.admin();
        skills.approve(skills.openVersion(id).orElseThrow().getId(), null);
        assertThat(skills.get(id).getInstructions()).isEqualTo("v2 rules");
        assertThat(skills.get(id).getLiveVersion()).isEqualTo(2);
    }

    @Test
    void rejectionNeedsACommentAndTheAuthorContinuesFromTheRejectedVersion() {
        UUID id = approvedSkill(unique("Reject"));
        AsUser.owner();
        skills.saveDraft(id, content(skills.get(id).getName(), "risky change"));
        skills.submit(id, "n");
        UUID versionId = skills.openVersion(id).orElseThrow().getId();
        AsUser.admin();
        assertThatThrownBy(() -> skills.reject(versionId, " ")).isInstanceOf(InvalidInputException.class);
        skills.reject(versionId, "Too risky");
        assertThat(skills.get(id).getInstructions()).isEqualTo("v1 rules");
        assertThat(skills.openVersion(id)).isEmpty();
        AsUser.owner();
        assertThat(skills.editable(id).instructions()).isEqualTo("risky change");
        skills.saveDraft(id, content(skills.get(id).getName(), "safer change"));
        assertThat(skills.openVersion(id).orElseThrow().getVersionNumber()).isEqualTo(3);
        assertThat(skills.history(id)).extracting(v -> v.getStatus()).containsExactly(ChangeStatus.DRAFT, ChangeStatus.REJECTED, ChangeStatus.APPROVED);
    }

    @Test
    void onlyTheAuthorWithdrawsAndADraftCanBeDiscarded() {
        UUID id = approvedSkill(unique("Withdraw"));
        AsUser.owner();
        skills.saveDraft(id, content(skills.get(id).getName(), "w"));
        skills.discardDraft(id);
        assertThat(skills.openVersion(id)).isEmpty();
        skills.saveDraft(id, content(skills.get(id).getName(), "w2"));
        skills.submit(id, "n");
        AsUser.admin();
        assertThatThrownBy(() -> skills.withdraw(id)).isInstanceOf(AccessDeniedException.class);
        AsUser.owner();
        skills.withdraw(id);
        assertThat(skills.openVersion(id)).isEmpty();
        assertThat(skills.get(id).getLifecycle()).isEqualTo(Lifecycle.APPROVED);
    }

    @Test
    void retireIsBlockedWhileAnApprovedAgentUsesTheSkillAndRestoreNeedsNewApproval() {
        UUID skillId = approvedSkill(unique("Used"));
        AsUser.owner();
        Agent agent = agents.create(unique("User"), "j", List.of(skillId), List.of());
        Approve.agent(agents, agent.getId());
        AsUser.admin();
        assertThatThrownBy(() -> skills.retire(skillId)).isInstanceOf(InvalidInputException.class).hasMessage("error.skill.inUse");

        UUID free = approvedSkill(unique("Free"));
        AsUser.admin();
        skills.retire(free);
        assertThat(skills.get(free).getLifecycle()).isEqualTo(Lifecycle.RETIRED);
        assertThat(skills.approved()).extracting(Skill::getId).doesNotContain(free);
        skills.restore(free);
        assertThat(skills.get(free).getLifecycle()).isEqualTo(Lifecycle.DRAFT);
        assertThat(skills.get(free).isUsable()).isFalse();
        skills.submit(free, "Back again");
        AsUser.admin2();
        skills.approve(skills.openVersion(free).orElseThrow().getId(), null);
        assertThat(skills.get(free).isUsable()).isTrue();
    }

    @Test
    void onlyNeverApprovedOrRetiredItemsCanBeDeleted() {
        UUID approved = approvedSkill(unique("Keep"));
        AsUser.owner();
        assertThatThrownBy(() -> skills.delete(approved)).isInstanceOf(InvalidInputException.class).hasMessage("error.lifecycle.cannotDelete");
        Skill draft = skills.create(content(unique("Drop"), "x"));
        skills.delete(draft.getId());
        assertThat(jdbc.queryForObject("select count(*) from skill where id = ?", Integer.class, draft.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from skill_version where item_id = ?", Integer.class, draft.getId())).isZero();
    }

    // ----- agents -----

    @Test
    void anAgentCanOnlyBeSubmittedWithApprovedSkills() {
        AsUser.owner();
        Skill draftSkill = skills.create(content(unique("Draft"), "x"));
        Agent agent = agents.create(unique("Needs"), "j", List.of(draftSkill.getId()), List.of());
        assertThatThrownBy(() -> agents.submit(agent.getId(), "n")).isInstanceOf(InvalidInputException.class).hasMessage("error.agent.skillNotApproved");
        Approve.skill(skills, draftSkill.getId());
        agents.submit(agent.getId(), "n");
        assertThat(agents.openVersion(agent.getId()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.PENDING);
    }

    @Test
    void agentChangesGoLiveOnlyAfterApprovalAndActivationNeedsAnApprovedAgent() {
        AsUser.owner();
        Agent agent = agents.create(unique("Live"), "old job", List.of(), List.of());
        assertThatThrownBy(() -> agents.setStatus(agent.getId(), AgentStatus.ACTIVE)).isInstanceOf(InvalidInputException.class);
        Approve.agent(agents, agent.getId());
        assertThat(agents.get(agent.getId()).getLiveVersion()).isEqualTo(1);

        AsUser.owner();
        agents.update(agent.getId(), agent.getName(), "new job", "i", Autonomy.ACT, ModelTier.FAST);
        assertThat(agents.get(agent.getId()).getJob()).isEqualTo("old job");
        assertThat(agents.editable(agent.getId()).job()).isEqualTo("new job");
        Approve.agent(agents, agent.getId());
        assertThat(agents.get(agent.getId()).getJob()).isEqualTo("new job");
        assertThat(agents.get(agent.getId()).getModelTier()).isEqualTo(ModelTier.FAST);
    }

    @Test
    void pausingStaysImmediateWithoutApproval() {
        AsUser.owner();
        Agent agent = agents.create(unique("Pause"), "j", List.of(), List.of());
        Approve.agent(agents, agent.getId());
        AsUser.owner();
        agents.setStatus(agent.getId(), AgentStatus.PAUSED);
        assertThat(agents.get(agent.getId()).getStatus()).isEqualTo(AgentStatus.PAUSED);
    }

    @Test
    void onlyApprovedAgentsAnswerButTheOwnerCanTestTheDraft() {
        AsUser.owner();
        Agent agent = agents.create(unique("Chatty"), "j", List.of(), List.of());
        UUID id = agent.getId();
        assertThat(chat.send(id, null, "Hello there").status()).isEqualTo(ChatReply.Status.REFUSED);
        assertThat(model.calls).isEmpty();

        ChatReply draft = chat.send(id, null, "Hello there", true);
        assertThat(draft.status()).isEqualTo(ChatReply.Status.ANSWERED);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where event like '%testing a draft%' and agent_id = ?", Integer.class, id)).isPositive();

        AsUser.admin2();
        assertThat(chat.send(id, null, "Hello", true).status()).isEqualTo(ChatReply.Status.ANSWERED); // Admins may

        Approve.agent(agents, id);
        AsUser.owner();
        assertThat(chat.send(id, null, "Hello again").status()).isEqualTo(ChatReply.Status.ANSWERED);
    }

    @Test
    void theDraftTestUsesTheDraftConfigurationAndLiveRunsUseTheApprovedOne() {
        AsUser.owner();
        Agent agent = agents.create(unique("Config"), "j", List.of(), List.of());
        Approve.agent(agents, agent.getId());
        AsUser.owner();
        agents.update(agent.getId(), agent.getName(), "j", "Always answer in pirate speak", Autonomy.ASK, ModelTier.DEEP);
        chat.send(agent.getId(), null, "Hello live", false);
        assertThat(model.last().system()).doesNotContain("pirate");
        chat.send(agent.getId(), null, "Hello draft", true);
        assertThat(model.last().system()).contains("pirate");
        assertThat(model.last().model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void unapprovedSkillsAreNotInTheLivePrompt() {
        UUID approved = approvedSkill(unique("Good"));
        AsUser.owner();
        Skill draftSkill = skills.create(content(unique("Wip"), "work in progress"));
        Agent agent = agents.create(unique("Mix"), "j", List.of(approved, draftSkill.getId()), List.of());
        // approve the agent without the draft skill, then the draft skill stays out of the live prompt
        agents.removeSkill(agent.getId(), draftSkill.getId());
        Approve.agent(agents, agent.getId());
        AsUser.owner();
        agents.addSkill(agent.getId(), draftSkill.getId());
        chat.send(agent.getId(), null, "Hello live", false);
        assertThat(model.last().system()).doesNotContain("work in progress");
        chat.send(agent.getId(), null, "Hello draft", true);
        assertThat(model.last().system()).contains("work in progress");
    }

    // ----- baseline for content from before the lifecycle -----

    @Test
    void contentWithoutVersionRecordsGetsAnApprovedBaselineVersion() throws Exception {
        UUID owner = jdbc.queryForObject("select id from app_user where username = 'owner'", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into skill (id, name, type, live_version, lifecycle_status, created_by, created_at, updated_at) "
                + "values (?, 'Legacy', 'INSTRUCTION', 1, 'APPROVED', ?, current_timestamp, current_timestamp)", id, owner);
        assertThat(jdbc.queryForObject("select count(*) from skill_version where item_id = ?", Integer.class, id)).isZero();
        ApplicationRunner baseline = context.getBeansOfType(ApplicationRunner.class).values().stream()
                .filter(r -> r.getClass().getName().contains("ApprovedBaseline")).findFirst().orElseThrow();
        baseline.run(null);
        assertThat(jdbc.queryForObject("select status from skill_version where item_id = ?", String.class, id)).isEqualTo("APPROVED");
        baseline.run(null); // idempotent
        assertThat(jdbc.queryForObject("select count(*) from skill_version where item_id = ?", Integer.class, id)).isEqualTo(1);
    }
}
