package com.agenticpilot.runtime;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.common.RateLimiter;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The test chat for signed-in users. The work is done by {@link ChatPipeline}; this adds the permission check. */
@Service
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AgentChatService {

    public static final int MAX_MESSAGE_CHARS = ChatPipeline.MAX_MESSAGE_CHARS;

    private final ChatPipeline pipeline;
    private final CurrentUser currentUser;
    private final AgentRepository agents;
    private final RateLimiter limiter;

    public AgentChatService(ChatPipeline pipeline, CurrentUser currentUser, AgentRepository agents,
            @org.springframework.beans.factory.annotation.Value("${agenticpilot.chat.rate-limit-per-minute:30}") int perMinute) {
        this.pipeline = pipeline;
        this.currentUser = currentUser;
        this.agents = agents;
        this.limiter = new RateLimiter(perMinute);
    }

    /** Talks to the approved version of the agent. */
    public ChatReply send(UUID agentId, UUID conversationId, String userText) {
        return send(agentId, conversationId, userText, false);
    }

    /**
     * With draft = true the agent's draft is tested instead of its approved version. Only its owner or an Admin may do that,
     * and the audit log says so.
     */
    public ChatReply send(UUID agentId, UUID conversationId, String userText, boolean draft) {
        var me = currentUser.require();
        if (draft) {
            Agent agent = readOwner(agentId);
            currentUser.requireEdit(agent.getOwner());
        }
        RateLimiter.Result limit = limiter.tryAcquire(me.getId().toString());
        if (!limit.allowed()) {
            throw new InvalidInputException("error.chat.rate", limit.retryAfterSeconds());
        }
        ChatActor actor = ChatActor.of(me);
        return pipeline.send(draft ? actor.testingDraft() : actor, agentId, conversationId, userText);
    }

    @Transactional(readOnly = true)
    Agent readOwner(UUID agentId) {
        return agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent"));
    }
}
