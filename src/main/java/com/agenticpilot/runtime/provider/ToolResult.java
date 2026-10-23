package com.agenticpilot.runtime.provider;

/** What the program answers to one tool request of the model. Errors are results too, so the model can correct itself. */
public record ToolResult(String toolCallId, String content, boolean error) {
}
