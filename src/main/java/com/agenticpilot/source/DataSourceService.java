package com.agenticpilot.source;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.source.connector.FetchException;
import com.agenticpilot.source.connector.WebsiteConnector;
import com.agenticpilot.source.connector.WebsiteProperties;
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
    private final AllowedDomainService allowedDomains;
    private final WebsiteProperties props;

    public DataSourceService(DataSourceRepository sources, CurrentUser currentUser, AuditService audit, AllowedDomainService allowedDomains,
                             WebsiteProperties props) {
        this.allowedDomains = allowedDomains;
        this.props = props;
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
            try {
                WebsiteConnector.parse(loc, props.allowPrivateAddresses());
            } catch (FetchException e) {
                throw new InvalidInputException(e.getMessage());
            }
            if (!allowedDomains.isAllowed(java.net.URI.create(loc).getHost())) {
                throw new InvalidInputException("error.domain.notAllowed");
            }
        }
        DataSource created = sources.save(new DataSource(n, kind, loc, currentUser.require()));
        audit.workspace("Data source \u201c" + n + "\u201d connected", AuditOutcome.ALLOWED, null, Map.of("kind", kind.name()));
        return created;
    }
}
