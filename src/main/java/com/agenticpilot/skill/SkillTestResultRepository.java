package com.agenticpilot.skill;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface SkillTestResultRepository extends JpaRepository<SkillTestResult, UUID> {

    List<SkillTestResult> findBySkillIdAndVersionNumberOrderByRunAtDesc(UUID skillId, int versionNumber);

    @Transactional
    void deleteByCaseIdAndVersionNumber(UUID caseId, int versionNumber);
}
