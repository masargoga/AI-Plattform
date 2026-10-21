package com.agenticpilot.guardrail;

import java.util.UUID;

/** A live custom rule as the engine needs it: already parsed, no database entity. */
public record RuleSpec(UUID id, String name, CatchPattern pattern, RuleAction action, String message) {
}
