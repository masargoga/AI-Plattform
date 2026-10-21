package com.agenticpilot.runtime.provider;

/** One message of a conversation sent to the model. The role is "user" or "assistant". */
public record ChatMessage(String role, String text) {

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text);
    }
}
