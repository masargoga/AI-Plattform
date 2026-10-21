package com.agenticpilot.source;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.user.AppUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The domains the website connector may crawl. Only Admins change the list; with an empty list nothing is crawled.
 * Everyone can read it, so they know whom to ask.
 */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class AllowedDomainService {

    private static final Pattern DOMAIN = Pattern.compile("^(?=.{4,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private final AllowedDomainRepository domains;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public AllowedDomainService(AllowedDomainRepository domains, CurrentUser currentUser, AuditService audit) {
        this.domains = domains;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AllowedDomain> all() {
        return domains.findAllByOrderByDomain();
    }

    public AllowedDomain add(String domain) {
        AppUser me = currentUser.requireAdmin();
        String d = normalise(domain);
        if (!DOMAIN.matcher(d).matches()) {
            throw new InvalidInputException("error.domain.invalid");
        }
        if (domains.existsByDomain(d)) {
            throw new InvalidInputException("error.domain.exists");
        }
        AllowedDomain saved = domains.save(new AllowedDomain(d, me));
        audit.workspace("Domain " + d + " allowed for website sources by " + me.getDisplayName(), AuditOutcome.ALLOWED, null, Map.of("domain", d));
        return saved;
    }

    public void remove(UUID id) {
        AppUser me = currentUser.requireAdmin();
        AllowedDomain d = domains.findById(id).orElseThrow(() -> new NotFoundException("Domain"));
        domains.delete(d);
        audit.workspace("Domain " + d.getDomain() + " removed from the allow list by " + me.getDisplayName(), AuditOutcome.ALLOWED, null, Map.of("domain", d.getDomain()));
    }

    /** True when the host is a listed domain or one of its subdomains. */
    @Transactional(readOnly = true)
    public boolean isAllowed(String host) {
        return matches(domains.findAllByOrderByDomain().stream().map(AllowedDomain::getDomain).toList(), host);
    }

    public static boolean matches(List<String> allowed, String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(a -> h.equals(a) || h.endsWith("." + a));
    }

    /** Accepts a pasted address too: scheme, path and port are dropped. */
    static String normalise(String input) {
        String d = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^[a-z]+://", "");
        int cut = d.indexOf('/');
        if (cut >= 0) {
            d = d.substring(0, cut);
        }
        d = d.replaceFirst(":\\d+$", "");
        return d.startsWith("*.") ? d.substring(2) : d;
    }
}
