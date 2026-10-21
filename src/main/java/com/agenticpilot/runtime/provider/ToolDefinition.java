package com.agenticpilot.runtime.provider;

/** A tool the model may call. The input schema is a JSON Schema object as text. */
public record ToolDefinition(String name, String description, String inputSchemaJson) {
}
