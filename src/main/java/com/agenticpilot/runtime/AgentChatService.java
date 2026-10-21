package com.agenticpilot.runtime;

import com.agenticpilot.common.CurrentUser;
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

    public AgentChatService(ChatPipeline pipeline, CurrentUser currentUser) {
        this.pipeline = pipeline;
        this.currentUser = currentUser;
    }

    public ChatReply send(UUID agentId, UUID conversationId, String userText) {
        return pipeline.send(ChatActor.of(currentUser.require()), agentId, conversationId, userText);
    }
}
