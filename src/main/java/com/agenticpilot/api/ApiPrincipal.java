package com.agenticpilot.api;

import java.util.UUID;

/** Who is calling the API: one key, which belongs to one agent. */
public record ApiPrincipal(UUID keyId, String prefix, UUID agentId) {
}
