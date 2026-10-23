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

    /** pattern is a LIKE pattern in lower case that escapes with '!' ("%" matches everything); the time range is from (inclusive) to to (exclusive). */
    @Query("""
            select e from AuditEntry e
            where (:outcome is null or e.outcome = :outcome)
              and (:agentId is null or e.agentId = :agentId)
              and e.occurredAt >= :from and e.occurredAt < :to
              and (lower(e.event) like :pattern escape '!' or lower(e.actor) like :pattern escape '!'
                   or lower(coalesce(e.agentName, '')) like :pattern escape '!')
            order by e.occurredAt desc
            """)
    List<AuditEntry> find(@Param("outcome") AuditOutcome outcome, @Param("agentId") UUID agentId, @Param("pattern") String pattern,
                          @Param("from") java.time.Instant from, @Param("to") java.time.Instant to, Pageable page);
}
