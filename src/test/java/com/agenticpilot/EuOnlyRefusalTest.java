package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.runtime.AgentChatService;
import com.agenticpilot.runtime.ChatReply;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.support.FakeModelConfig;
import com.agenticpilot.support.FakeModelConfig.FakeModel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** With no EU-approved endpoint configured (the default), EU-only agents fail closed. */
@SpringBootTest(properties = "agenticpilot.model.eu-allowed=false")
@ActiveProfiles("test")
@Import(FakeModelConfig.class)
class EuOnlyRefusalTest {

    @Autowired AgentChatService chat;
    @Autowired AgentService agents;
    @Autowired AgentGuardrailService guardrails;
    @Autowired AuditService audit;
    @Autowired FakeModel model;

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    @Test
    void euOnlyAgentsRefuseToRunWithoutAnEuEndpointAndAnywhereAgentsStillRun() {
        model.reset();
        AsUser.admin();
        Agent euAgent = agents.create("EU agent " + System.nanoTime(), "j", List.of(), List.of());
        ChatReply refused = chat.send(euAgent.getId(), null, "Hello there");
        assertThat(refused.status()).isEqualTo(ChatReply.Status.REFUSED);
        assertThat(refused.text()).contains("EU-only");
        assertThat(model.calls).isEmpty();
        assertThat(audit.latest(AuditOutcome.BLOCKED, 3)).anyMatch(e -> e.getEvent().startsWith("Request refused: EU-only"));

        guardrails.setRegion(euAgent.getId(), Region.ANY);
        assertThat(chat.send(euAgent.getId(), null, "Hello again").status()).isEqualTo(ChatReply.Status.ANSWERED);
        assertThat(model.calls).hasSize(1);
    }
}
