package com.agenticpilot.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Deliberately not a JpaRepository: there is no update and no delete for audit entries. */
public interface AuditEntryRepository extends Repository<AuditEntry, UUID> {

    AuditEntry save(AuditEntry entry);

    long count();

    @Query("select e from AuditEntry e where (:outcome is null or e.outcome = :outcome) order by e.occurredAt desc")
    List<AuditEntry> search(@Param("outcome") AuditOutcome outcome, Pageable page);
}
