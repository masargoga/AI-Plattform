package com.agenticpilot.compliance;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRequestRepository extends JpaRepository<SubjectRequest, UUID> {

    List<SubjectRequest> findAllByOrderByDueDateAsc();

    int countByDoneAtIsNull();
}
