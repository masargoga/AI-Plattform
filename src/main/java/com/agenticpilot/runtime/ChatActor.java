package com.agenticpilot.runtime;

import com.agenticpilot.user.AppUser;
import java.util.UUID;

/** Who is talking to an agent: a signed-in user in the test chat, or an API key. */
public record ChatActor(AppUser user, String auditName, ConversationChannel channel, UUID apiKeyId) {

    public static ChatActor of(AppUser user) {
        return new ChatActor(user, user.getUsername(), ConversationChannel.TEST, null);
    }

    public static ChatActor api(UUID apiKeyId, String keyPrefix) {
        return new ChatActor(null, "api:" + keyPrefix, ConversationChannel.API, apiKeyId);
    }
}
