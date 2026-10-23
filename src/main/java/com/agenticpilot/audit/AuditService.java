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
        return recordAs(currentUser.usernameOrSystem(), agentId, agentName, event, outcome, ruleId, details);
    }

    /** Records an event for an explicit actor, for example "api:ab12cd34" for a call made with an API key. */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEntry recordAs(String actor, UUID agentId, String agentName, String event, AuditOutcome outcome, UUID ruleId, Map<String, ?> details) {
        return entries.save(new AuditEntry(Instant.now(), agentId, agentName, actor, event, outcome,
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

    /** What the Audit log screen filters by. Everything is optional; dates are in UTC and both ends count. */
    public record Filter(AuditOutcome outcome, UUID agentId, String text, java.time.LocalDate from, java.time.LocalDate to) {

        public static Filter none() {
            return new Filter(null, null, "", null, null);
        }

        public Filter withOutcome(AuditOutcome o) {
            return new Filter(o, agentId, text, from, to);
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
    @Transactional(readOnly = true)
    public List<AuditEntry> search(Filter filter, int limit) {
        return find(filter, Math.min(Math.max(limit, 1), MAX_ROWS));
    }

    private List<AuditEntry> find(Filter f, int limit) {
        String text = f.text() == null ? "" : f.text().strip().toLowerCase(java.util.Locale.ROOT);
        String pattern = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Instant from = f.from() == null ? Instant.EPOCH : f.from().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = f.to() == null ? Instant.parse("3000-01-01T00:00:00Z") : f.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return entries.find(f.outcome(), f.agentId(), pattern, from, to, PageRequest.of(0, limit));
    }

    /** CSV of the newest entries. Cells that could be read as formulas by a spreadsheet are neutralised. */
    @PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
    @Transactional(readOnly = true)
    public String exportCsv(AuditOutcome outcome) {
        return exportCsv(Filter.none().withOutcome(outcome));
    }

    /** CSV of the entries that match the same filter as the screen. */
    @PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
    @Transactional(readOnly = true)
    public String exportCsv(Filter filter) {
        StringBuilder sb = new StringBuilder("time,agent,actor,event,outcome\r\n");
        for (AuditEntry e : find(filter, 10_000)) {
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
