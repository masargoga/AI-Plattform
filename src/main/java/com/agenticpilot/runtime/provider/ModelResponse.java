package com.agenticpilot.runtime.provider;

import java.util.List;

public record ModelResponse(String text, List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
}
