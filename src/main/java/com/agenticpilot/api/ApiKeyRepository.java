package com.agenticpilot.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findByAgentIdOrderByCreatedAtDesc(UUID agentId);
}
