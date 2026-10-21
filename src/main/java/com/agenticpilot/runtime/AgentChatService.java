package com.agenticpilot.runtime;

import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.RateLimiter;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** The test chat for signed-in users. The work is done by {@link ChatPipeline}; this adds the permission check. */
@Service
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AgentChatService {

    public static final int MAX_MESSAGE_CHARS = ChatPipeline.MAX_MESSAGE_CHARS;

    private final ChatPipeline pipeline;
    private final CurrentUser currentUser;
    private final RateLimiter limiter;

    public AgentChatService(ChatPipeline pipeline, CurrentUser currentUser,
            @org.springframework.beans.factory.annotation.Value("${agenticpilot.chat.rate-limit-per-minute:30}") int perMinute) {
        this.pipeline = pipeline;
        this.currentUser = currentUser;
        this.limiter = new RateLimiter(perMinute);
    }

    public ChatReply send(UUID agentId, UUID conversationId, String userText) {
        var me = currentUser.require();
        RateLimiter.Result limit = limiter.tryAcquire(me.getId().toString());
        if (!limit.allowed()) {
            throw new InvalidInputException("error.chat.rate", limit.retryAfterSeconds());
        }
        return pipeline.send(ChatActor.of(me), agentId, conversationId, userText);
    }
}
