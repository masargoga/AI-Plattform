package com.agenticpilot.skill;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    @Query("""
            select s from Skill s
            where (:type is null or s.type = :type)
              and (:q = '' or lower(s.name) like concat('%', :q, '%') or lower(s.description) like concat('%', :q, '%'))
            order by s.name
            """)
    List<Skill> search(@Param("type") SkillType type, @Param("q") String q);
}
