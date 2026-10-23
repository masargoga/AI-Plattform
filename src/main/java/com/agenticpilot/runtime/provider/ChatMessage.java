package com.agenticpilot.runtime.provider;

import java.util.List;

/**
 * One message of a conversation sent to the model. The role is "user" or "assistant". An assistant message may carry the tool
 * requests the model made; the user message that follows carries the results of those requests.
 */
public record ChatMessage(String role, String text, List<ToolCall> toolCalls, List<ToolResult> toolResults) {

    public ChatMessage(String role, String text) {
        this(role, text, List.of(), List.of());
    }

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text);
    }

    /** The model's turn when it asked for tools (the text may be empty). */
    public static ChatMessage assistantWithTools(String text, List<ToolCall> calls) {
        return new ChatMessage("assistant", text == null ? "" : text, List.copyOf(calls), List.of());
    }

    /** The program's answer to the tool requests of the previous assistant message. */
    public static ChatMessage toolResults(List<ToolResult> results) {
        return new ChatMessage("user", "", List.of(), List.copyOf(results));
    }

    /** Characters this message adds to the prompt, for the size limit. */
    public int size() {
        int n = text == null ? 0 : text.length();
        for (ToolCall c : toolCalls) {
            n += c.name().length() + c.inputJson().length();
        }
        for (ToolResult r : toolResults) {
            n += r.content().length();
        }
        return n;
    }
}
