package com.agenticpilot.source;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
}
