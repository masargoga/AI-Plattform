package com.agenticpilot.compliance;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.guardrail.VersionStatus;
import com.agenticpilot.guardrail.RuleVersionRepository;
import com.agenticpilot.user.AppUser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Compliance records and data subject requests. Everyone can read them; only Admins complete them. */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class ComplianceService {

    private final ComplianceRecordRepository records;
    private final SubjectRequestRepository requests;
    private final RuleVersionRepository ruleVersions;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public ComplianceService(ComplianceRecordRepository records, SubjectRequestRepository requests,
                             RuleVersionRepository ruleVersions, AuditService audit, CurrentUser currentUser) {
        this.records = records;
        this.requests = requests;
        this.ruleVersions = ruleVersions;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ComplianceRecord> records() {
        return records.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public List<SubjectRequest> requests() {
        return requests.findAllByOrderByDueDateAsc();
    }

    public void completeRecord(UUID id) {
        AppUser me = currentUser.requireAdmin();
        ComplianceRecord r = records.findById(id).orElseThrow(() -> new NotFoundException("Record"));
        if (r.getStatus() == RecordStatus.DONE) {
            return;
        }
        r.markDone(LocalDate.now());
        audit.workspace("Compliance record “" + r.getTitle() + "” marked done by " + me.getDisplayName(), AuditOutcome.ALLOWED, null, Map.of());
    }

    public void completeRequest(UUID id) {
        AppUser me = currentUser.requireAdmin();
        SubjectRequest r = requests.findById(id).orElseThrow(() -> new NotFoundException("Request"));
        if (r.isDone()) {
            return;
        }
        r.complete(Instant.now());
        audit.workspace("Data subject request closed by " + me.getDisplayName(), AuditOutcome.ALLOWED, null,
                Map.of("kind", r.getKind().name()));
    }

    /** Open records, open data subject requests and rule changes waiting for approval: the count next to Policies. */
    @Transactional(readOnly = true)
    public int openItems() {
        return records.countByStatusNot(RecordStatus.DONE) + requests.countByDoneAtIsNull() + ruleVersions.countByStatus(VersionStatus.PENDING);
    }
}
