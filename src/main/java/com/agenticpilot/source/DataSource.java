package com.agenticpilot.source;

import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "data_source")
public class DataSource extends AuditedEntity {

    /** A source not synced for longer than this shows a warning. */
    public static final Duration STALE_AFTER = Duration.ofDays(5);

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SourceKind kind;

    @Column(nullable = false, length = 500)
    private String location;

    /** Connector specific settings as JSON. */
    @Column(length = 4000)
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SyncStatus status = SyncStatus.SYNCED;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "last_synced")
    private Instant lastSynced;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "owner_name", nullable = false, length = 80)
    private String ownerName = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 16)
    private TrustLevel trustLevel = TrustLevel.INFORMATIONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_failure", nullable = false, length = 16)
    private OnFailure onFailure = OnFailure.USE_LAST_COPY;

    /** A copy older than this many days counts as stale. */
    @Column(name = "max_age_days", nullable = false)
    private int maxAgeDays = 5;

    @Column(name = "review_by")
    private LocalDate reviewBy;

    @Column(name = "version_info", nullable = false, length = 200)
    private String versionInfo = "";

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    protected DataSource() {
    }

    public DataSource(String name, SourceKind kind, String location, AppUser createdBy) {
        this.name = name;
        this.kind = kind;
        this.location = location;
        this.createdBy = createdBy;
        this.status = SyncStatus.SYNCED;
    }

    public boolean isStale(Instant now) {
        return lastSynced == null || Duration.between(lastSynced, now).compareTo(Duration.ofDays(maxAgeDays)) > 0;
    }

    /** With STOP_AND_REPORT a source in error or too old is left out of answers. */
    public boolean isUnavailable(Instant now) {
        return onFailure == OnFailure.STOP_AND_REPORT && (status == SyncStatus.ERROR || isStale(now));
    }

    public boolean isReviewOverdue(LocalDate today) {
        return reviewBy != null && reviewBy.isBefore(today);
    }

    public void applySettings(String ownerName, TrustLevel trustLevel, OnFailure onFailure, int maxAgeDays, LocalDate reviewBy, String versionInfo) {
        this.ownerName = ownerName;
        this.trustLevel = trustLevel;
        this.onFailure = onFailure;
        this.maxAgeDays = maxAgeDays;
        this.reviewBy = reviewBy;
        this.versionInfo = versionInfo;
    }

    public String getOwnerName() { return ownerName; }
    public TrustLevel getTrustLevel() { return trustLevel; }
    public OnFailure getOnFailure() { return onFailure; }
    public int getMaxAgeDays() { return maxAgeDays; }
    public LocalDate getReviewBy() { return reviewBy; }
    public String getVersionInfo() { return versionInfo; }

    public String getName() { return name; }
    public SourceKind getKind() { return kind; }
    public String getLocation() { return location; }
    public String getConfig() { return config; }
    public SyncStatus getStatus() { return status; }
    public int getItemCount() { return itemCount; }
    public Instant getLastSynced() { return lastSynced; }
    public String getLastError() { return lastError; }
    public AppUser getCreatedBy() { return createdBy; }

    public void markSynced(int itemCount, Instant when) {
        this.status = SyncStatus.SYNCED;
        this.itemCount = itemCount;
        this.lastSynced = when;
        this.lastError = null;
    }

    public void markSyncing() {
        this.status = SyncStatus.SYNCING;
    }

    /** The last good content stays; only the status and the reason change. */
    public void markError(String reason) {
        this.status = SyncStatus.ERROR;
        this.lastError = reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    public void markNeverSynced() {
        this.lastSynced = null;
        this.itemCount = 0;
    }
}
