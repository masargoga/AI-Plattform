package com.agenticpilot.guardrail;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleVersionRepository extends JpaRepository<RuleVersion, UUID> {

    Optional<RuleVersion> findByRuleIdAndStatus(UUID ruleId, VersionStatus status);

    List<RuleVersion> findByStatus(VersionStatus status);

    List<RuleVersion> findByRuleIdAndStatusInOrderByVersionNumberDesc(UUID ruleId, Collection<VersionStatus> statuses);

    int countByStatus(VersionStatus status);

    Optional<RuleVersion> findTopByRuleIdOrderByVersionNumberDesc(UUID ruleId);
}
