package com.agenticpilot.governance;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface ItemVersionRepository<V extends ItemVersion> extends JpaRepository<V, UUID> {

    /** The draft or pending version of an item (at most one). */
    Optional<V> findByItemIdAndOpenFlag(UUID itemId, Integer openFlag);

    List<V> findByItemIdOrderByVersionNumberDesc(UUID itemId);

    Optional<V> findTopByItemIdOrderByVersionNumberDesc(UUID itemId);

    List<V> findByItemIdInAndOpenFlag(Collection<UUID> itemIds, Integer openFlag);

    List<V> findByStatusOrderByCreatedAtAsc(ChangeStatus status);

    int countByStatus(ChangeStatus status);

    Optional<V> findByItemIdAndVersionNumber(UUID itemId, int versionNumber);
}
