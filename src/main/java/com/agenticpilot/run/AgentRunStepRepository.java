package com.agenticpilot.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentRunStepRepository extends JpaRepository<AgentRunStep, UUID> {

    List<AgentRunStep> findByRunIdOrderByOrdinal(UUID runId);

    @Modifying
    @Transactional
    @Query("delete from AgentRunStep s where s.runId = :runId")
    int deleteByRunId(@Param("runId") UUID runId);
}
