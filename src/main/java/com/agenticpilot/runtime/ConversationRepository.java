package com.agenticpilot.runtime;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Modifying
    @Query("delete from Conversation c where c.expiresAt is not null and c.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    long countByAgentId(UUID agentId);
}
