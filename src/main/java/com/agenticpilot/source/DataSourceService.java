package com.agenticpilot.source;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class DataSourceService {

    public static final int NAME_MAX = 80;

    private final DataSourceRepository sources;
    private final CurrentUser currentUser;

    private final AuditService audit;

    public DataSourceService(DataSourceRepository sources, CurrentUser currentUser, AuditService audit) {
        this.sources = sources;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DataSource> all() {
        return sources.findAllByOrderByName();
    }

    @Transactional(readOnly = true)
    public DataSource get(UUID id) {
        return sources.findById(id).orElseThrow(() -> new NotFoundException("Data source"));
    }

    @Transactional(readOnly = true)
    public boolean canEdit(DataSource source) {
        return currentUser.canEdit(source.getCreatedBy());
    }

    public DataSource create(String name, SourceKind kind, String location) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new InvalidInputException("error.nameRequired");
        }
        if (n.length() > NAME_MAX) {
            throw new InvalidInputException("error.tooLong", NAME_MAX);
        }
        if (kind == null || !kind.isAvailable()) {
            throw new InvalidInputException("error.kindUnavailable");
        }
        String loc = location == null ? "" : location.strip();
        if (loc.isEmpty()) {
            throw new InvalidInputException("error.locationRequired");
        }
        if (loc.length() > 500) {
            throw new InvalidInputException("error.tooLong", 500);
        }
        if (kind == SourceKind.WEBSITE) {
            requireHttpUrl(loc);
        }
        DataSource created = sources.save(new DataSource(n, kind, loc, currentUser.require()));
        audit.workspace("Data source \u201c" + n + "\u201d connected", AuditOutcome.ALLOWED, null, Map.of("kind", kind.name()));
        return created;
    }

    /**
     * Placeholder until the connectors land (uploads in M3, website in M4): it only records the time of the request.
     * It does not read any content yet.
     */
    public DataSource sync(UUID id) {
        DataSource source = get(id);
        currentUser.requireEdit(source.getCreatedBy());
        source.markSynced(source.getItemCount(), Instant.now());
        audit.workspace("Sync requested for \u201c" + source.getName() + "\u201d", AuditOutcome.ALLOWED, null, Map.of());
        return source;
    }

    static void requireHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean ok = ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
            if (!ok) {
                throw new InvalidInputException("error.urlInvalid");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("error.urlInvalid");
        }
    }
}
