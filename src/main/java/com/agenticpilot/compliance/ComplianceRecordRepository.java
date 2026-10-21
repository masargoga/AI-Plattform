package com.agenticpilot.compliance;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceRecordRepository extends JpaRepository<ComplianceRecord, UUID> {

    List<ComplianceRecord> findAllByOrderByCreatedAtAsc();

    int countByStatusNot(RecordStatus status);
}
