package com.agenticpilot.runtime;

import com.agenticpilot.user.AppUser;
import java.util.UUID;

/** Who is talking to an agent: a signed-in user in the test chat, or an API key. */
public record ChatActor(AppUser user, String auditName, ConversationChannel channel, UUID apiKeyId, boolean draft) {

    public static ChatActor of(AppUser user) {
        return new ChatActor(user, user.getUsername(), ConversationChannel.TEST, null, false);
    }

    /** The same user, testing the draft of the agent instead of its approved version. The API never does this. */
    public ChatActor testingDraft() {
        return new ChatActor(user, auditName, channel, apiKeyId, true);
    }

    public static ChatActor api(UUID apiKeyId, String keyPrefix) {
        return new ChatActor(null, "api:" + keyPrefix, ConversationChannel.API, apiKeyId, false);
    }
}
