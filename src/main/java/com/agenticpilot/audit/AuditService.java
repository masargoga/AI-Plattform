package com.agenticpilot.audit;

import com.agenticpilot.common.CurrentUser;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Writes and reads the append-only audit log. Writing joins the caller's transaction, so a change and its entry commit together. */
@Service
public class AuditService {

    public static final int MAX_ROWS = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditEntryRepository entries;
    private final CurrentUser currentUser;

    public AuditService(AuditEntryRepository entries, CurrentUser currentUser) {
        this.entries = entries;
        this.currentUser = currentUser;
    }

    /** Records an event for the signed-in user (or "system"). Details must not contain personal values or secrets. */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEntry record(UUID agentId, String agentName, String event, AuditOutcome outcome, UUID ruleId, Map<String, ?> details) {
        return entries.save(new AuditEntry(Instant.now(), agentId, agentName, currentUser.usernameOrSystem(), event, outcome,
                ruleId, null, details == null || details.isEmpty() ? null : JSON.writeValueAsString(details)));
    }

    /** An entry about the workspace as a whole. */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEntry workspace(String event, AuditOutcome outcome, UUID ruleId, Map<String, ?> details) {
        return record(null, null, event, outcome, ruleId, details);
    }

    @PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
    @Transactional(readOnly = true)
    public List<AuditEntry> latest(AuditOutcome outcome, int limit) {
        return entries.search(outcome, PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_ROWS)));
    }

    /** CSV of the newest entries. Cells that could be read as formulas by a spreadsheet are neutralised. */
    @PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
    @Transactional(readOnly = true)
    public String exportCsv(AuditOutcome outcome) {
        StringBuilder sb = new StringBuilder("time,agent,actor,event,outcome\r\n");
        for (AuditEntry e : entries.search(outcome, PageRequest.of(0, 10_000))) {
            sb.append(DateTimeFormatter.ISO_INSTANT.format(e.getOccurredAt().atOffset(ZoneOffset.UTC))).append(',')
                    .append(cell(e.getAgentName() == null ? "Workspace" : e.getAgentName())).append(',')
                    .append(cell(e.getActor())).append(',')
                    .append(cell(e.getEvent())).append(',')
                    .append(e.getOutcome()).append("\r\n");
        }
        return sb.toString();
    }

    public static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
