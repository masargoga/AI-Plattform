package com.agenticpilot.run;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    List<AgentRun> findByAgentIdOrderByStartedAtDesc(UUID agentId, Pageable page);

    int countByAgentIdAndStatusIn(UUID agentId, Collection<RunStatus> statuses);

    int countByStatusIn(Collection<RunStatus> statuses);

    List<AgentRun> findByStatusInAndLastStepAtBefore(Collection<RunStatus> statuses, Instant before);

    @Modifying
    @Transactional
    @Query("delete from AgentRun r where r.expiresAt is not null and r.expiresAt < :now and r.status = com.agenticpilot.run.RunStatus.FINISHED")
    int deleteExpired(@Param("now") Instant now);
}
