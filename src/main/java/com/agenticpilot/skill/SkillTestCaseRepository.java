package com.agenticpilot.skill;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillTestCaseRepository extends JpaRepository<SkillTestCase, UUID> {

    List<SkillTestCase> findBySkillIdOrderByCreatedAtAsc(UUID skillId);

    int countBySkillId(UUID skillId);
}
