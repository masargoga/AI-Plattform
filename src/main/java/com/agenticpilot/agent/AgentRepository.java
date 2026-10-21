package com.agenticpilot.agent;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    @Query("""
            select a from Agent a
            where (:status is null or a.status = :status)
              and (:q = '' or lower(a.name) like concat('%', :q, '%') or lower(a.job) like concat('%', :q, '%'))
            order by a.name
            """)
    List<Agent> search(@Param("status") AgentStatus status, @Param("q") String q);

    List<Agent> findBySkills_IdOrderByName(UUID skillId);

    List<Agent> findBySources_IdOrderByName(UUID sourceId);
}
