package com.agenticpilot.runtime;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingActionRepository extends JpaRepository<PendingAction, UUID> {

    List<PendingAction> findByAuditEntryIdIn(Collection<UUID> auditEntryIds);

    int countByStatus(PendingActionStatus status);
}
