package com.agenticpilot.source;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A domain the website connector may crawl. A listed domain also covers its subdomains. */
@Entity
@Table(name = "allowed_domain")
public class AllowedDomain extends AuditedEntity {

    @Column(nullable = false, updatable = false, unique = true, length = 255)
    private String domain;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "added_by", updatable = false)
    private AppUser addedBy;

    protected AllowedDomain() {
    }

    public AllowedDomain(String domain, AppUser addedBy) {
        this.domain = domain;
        this.addedBy = addedBy;
    }

    public String getDomain() { return domain; }
    public AppUser getAddedBy() { return addedBy; }
}
