package com.agenticpilot.source;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {

    List<SourceDocument> findBySourceIdOrderByTitle(UUID sourceId);

    Optional<SourceDocument> findBySourceIdAndContentHash(UUID sourceId, String contentHash);

    int countBySourceId(UUID sourceId);
}
