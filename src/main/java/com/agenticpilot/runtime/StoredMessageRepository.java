package com.agenticpilot.runtime;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredMessageRepository extends JpaRepository<StoredMessage, UUID> {

    List<StoredMessage> findByConversationIdOrderByOrdinal(UUID conversationId);

    int countByConversationId(UUID conversationId);
}
