package com.agenticpilot.guardrail;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomRuleRepository extends JpaRepository<CustomRule, UUID> {

    List<CustomRule> findAllByOrderByCreatedAtAsc();
}
