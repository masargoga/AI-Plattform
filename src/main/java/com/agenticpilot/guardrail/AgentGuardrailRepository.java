package com.agenticpilot.guardrail;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentGuardrailRepository extends JpaRepository<AgentGuardrail, UUID> {
}
