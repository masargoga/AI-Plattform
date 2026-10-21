package com.agenticpilot.runtime.provider;

import java.time.Duration;
import java.util.List;

/** What a provider needs for one call. The model name is already resolved from the agent's tier. */
public record ModelRequest(String model, String system, List<ChatMessage> messages, List<ToolDefinition> tools, int maxTokens, Duration timeout) {
}
