package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.support.AsUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CoreObjectsServiceTest {

    @Autowired AgentService agents;
    @Autowired SkillService skills;
    @Autowired DataSourceService sources;

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    private static SkillService.Content content(String name) {
        return new SkillService.Content(name, SkillType.INSTRUCTION, "d", "t", "i", Set.of(), OutputFormat.BULLETS);
    }

    @Test
    void anonymousCannotUseServices() {
        assertThatThrownBy(() -> agents.search(null, "")).isInstanceOf(Exception.class);
    }

    @Test
    void creatorBecomesOwnerAndOthersCannotEditButAdminCan() {
        AsUser.owner();
        Agent a = agents.create("Owned " + System.nanoTime(), "job", List.of(), List.of());
        assertThat(a.getOwner().getUsername()).isEqualTo("owner");
        assertThat(agents.canEdit(a)).isTrue();

        AsUser.admin2();
        assertThat(agents.canEdit(agents.get(a.getId()))).isTrue();
        agents.setStatus(a.getId(), AgentStatus.PAUSED);
        assertThat(agents.get(a.getId()).getStatus()).isEqualTo(AgentStatus.PAUSED);
    }

    @Test
    void ownerCannotEditAgentOfAnother() {
        AsUser.admin();
        Agent a = agents.create("Admins " + System.nanoTime(), "job", List.of(), List.of());

        AsUser.owner();
        assertThat(agents.canEdit(agents.get(a.getId()))).isFalse();
        assertThatThrownBy(() -> agents.setStatus(a.getId(), AgentStatus.PAUSED)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> agents.update(a.getId(), "x", "y", "z", Autonomy.ACT, ModelTier.FAST))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void agentNeedsAName() {
        AsUser.owner();
        assertThatThrownBy(() -> agents.create("  ", "job", List.of(), List.of())).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void skillSavingRaisesVersionAndIsLimitedToCreatorOrAdmin() {
        AsUser.owner();
        Skill s = skills.create(content("Skill " + System.nanoTime()));
        assertThat(s.getRevision()).isEqualTo(1);
        assertThat(skills.update(s.getId(), content(s.getName())).getRevision()).isEqualTo(2);

        AsUser.admin();
        assertThat(skills.update(s.getId(), content(s.getName())).getRevision()).isEqualTo(3);

        AsUser.owner();
        Skill adminSkill;
        AsUser.admin();
        adminSkill = skills.create(content("Admin skill " + System.nanoTime()));
        AsUser.owner();
        UUID id = adminSkill.getId();
        assertThatThrownBy(() -> skills.update(id, content("Hijack"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void agentSkillsAndSourcesCanBeAttachedAndRemoved() {
        AsUser.owner();
        Skill s = skills.create(content("Attach " + System.nanoTime()));
        DataSource d = sources.create("Src " + System.nanoTime(), SourceKind.UPLOAD, "Collection");
        Agent a = agents.create("Link " + System.nanoTime(), "job", List.of(s.getId()), List.of());
        agents.addSource(a.getId(), d.getId());
        assertThat(agents.usingSkill(s.getId())).extracting(Agent::getId).contains(a.getId());
        assertThat(agents.usingSource(d.getId())).extracting(Agent::getId).contains(a.getId());
        agents.removeSkill(a.getId(), s.getId());
        agents.removeSource(a.getId(), d.getId());
        Agent reloaded = agents.get(a.getId());
        assertThat(reloaded.getSkills()).isEmpty();
        assertThat(reloaded.getSources()).isEmpty();
    }

    @Test
    void searchAndFilterWork() {
        AsUser.owner();
        String unique = "zqx" + System.nanoTime();
        Agent a = agents.create("Needle " + unique, "finds " + unique, List.of(), List.of());
        agents.setStatus(a.getId(), AgentStatus.PAUSED);
        assertThat(agents.search(null, unique.toUpperCase())).extracting(Agent::getId).containsExactly(a.getId());
        assertThat(agents.search(AgentStatus.ACTIVE, unique)).isEmpty();
        assertThat(agents.search(AgentStatus.PAUSED, unique)).hasSize(1);
    }

    @Test
    void onlyUploadAndWebsiteSourcesCanBeCreatedAndWebsitesNeedHttpUrls() {
        AsUser.owner();
        assertThatThrownBy(() -> sources.create("Wiki", SourceKind.WIKI, "x")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> sources.create("Site", SourceKind.WEBSITE, "ftp://example.com")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> sources.create("Site", SourceKind.WEBSITE, "not a url")).isInstanceOf(InvalidInputException.class);
        assertThat(sources.create("Site " + System.nanoTime(), SourceKind.WEBSITE, "https://example.com").getKind())
                .isEqualTo(SourceKind.WEBSITE);
    }

    @Test
    void sourceSyncIsLimitedToCreatorOrAdmin() {
        AsUser.admin();
        DataSource d = sources.create("Sync " + System.nanoTime(), SourceKind.UPLOAD, "Files");
        AsUser.owner();
        UUID id = d.getId();
        assertThatThrownBy(() -> sources.sync(id)).isInstanceOf(AccessDeniedException.class);
        AsUser.admin2();
        assertThat(sources.sync(id).getLastSynced()).isNotNull();
    }
}
