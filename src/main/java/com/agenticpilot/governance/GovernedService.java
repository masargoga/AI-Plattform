package com.agenticpilot.governance;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.AuditedEntity;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.user.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approval lifecycle shared by skills and agents. An item's own fields always equal its newest approved version
 * (the "live" version, which the runtime reads). Changes go into a draft version; submitting it asks another Admin, who
 * approves or rejects. The four-eyes rule is checked here and by the database.
 *
 * @param <E> the item (Skill or Agent)
 * @param <V> its version entity
 * @param <S> the snapshot: the editable fields of an item as stored in a version
 */
// Class-level annotations are read from the class that declares a method, so the inherited methods need their own.
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public abstract class GovernedService<E extends AuditedEntity & Governed, V extends ItemVersion, S> {

    public static final int NOTE_MAX = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    protected final JpaRepository<E, UUID> items;
    protected final ItemVersionRepository<V> versions;
    protected final CurrentUser currentUser;
    protected final AuditService audit;
    private final Class<S> snapshotType;
    private final String label;

    protected GovernedService(JpaRepository<E, UUID> items, ItemVersionRepository<V> versions, CurrentUser currentUser, AuditService audit,
                              Class<S> snapshotType, String label) {
        this.items = items;
        this.versions = versions;
        this.currentUser = currentUser;
        this.audit = audit;
        this.snapshotType = snapshotType;
        this.label = label;
    }

    // ----- hooks -----

    protected abstract S snapshotOf(E item);

    /** Copies the snapshot onto the item (the live fields). */
    protected abstract void apply(E item, S snapshot);

    protected abstract V newVersion(UUID itemId, int number, String json, AppUser author);

    protected abstract String nameOf(S snapshot);

    /** Throws when a snapshot may not be submitted or approved yet. */
    protected void checkReady(E item, S snapshot) {
    }

    /** Throws when the item is still in use and cannot be retired or deleted. */
    protected void checkUnused(E item) {
    }

    protected void record(E item, String event, AuditOutcome outcome, Map<String, ?> details) {
        audit.workspace(label + " “" + item.getName() + "” " + event, outcome, null, details);
    }

    // ----- reading -----

    public E get(UUID id) {
        return items.findById(id).orElseThrow(() -> new NotFoundException(label));
    }

    public Optional<V> openVersion(UUID id) {
        return versions.findByItemIdAndOpenFlag(id, 1);
    }

    public List<V> history(UUID id) {
        return versions.findByItemIdOrderByVersionNumberDesc(id);
    }

    public List<V> pendingVersions() {
        return versions.findByStatusOrderByCreatedAtAsc(ChangeStatus.PENDING);
    }

    public int pendingCount() {
        return versions.countByStatus(ChangeStatus.PENDING);
    }

    /** The open (draft or pending) version of each of the items, by item id. */
    public Map<UUID, V> openVersions(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, V> map = new java.util.HashMap<>();
        versions.findByItemIdInAndOpenFlag(ids, 1).forEach(v -> map.put(v.getItemId(), v));
        return map;
    }

    protected static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    public S parse(V version) {
        return JSON.readValue(version.getSnapshotJson(), snapshotType);
    }

    public V version(UUID versionId) {
        return versions.findById(versionId).orElseThrow(() -> new NotFoundException("Version"));
    }

    /**
     * What the editor shows: the open version, else the newest rejected or withdrawn version when it is newer than the live
     * one (the author continues from it), else the live fields.
     */
    public S editable(UUID id) {
        E item = get(id);
        Optional<V> open = openVersion(id);
        if (open.isPresent()) {
            return parse(open.get());
        }
        Optional<V> latest = versions.findTopByItemIdOrderByVersionNumberDesc(id);
        if (latest.isPresent() && latest.get().getVersionNumber() > item.getLiveVersion()
                && (latest.get().getStatus() == ChangeStatus.REJECTED || latest.get().getStatus() == ChangeStatus.WITHDRAWN)) {
            return parse(latest.get());
        }
        return snapshotOf(item);
    }

    // ----- the author's steps -----

    /** Stores the edit in the draft (creating one when needed). A never approved item shows the draft content itself. */
    protected E saveDraftInternal(UUID id, S content) {
        E item = get(id);
        currentUser.requireEdit(item.getResponsible());
        if (item.getLifecycle() == Lifecycle.RETIRED) {
            throw new InvalidInputException("error.lifecycle.retired");
        }
        AppUser me = currentUser.require();
        String json = JSON.writeValueAsString(content);
        Optional<V> open = openVersion(id);
        if (open.isPresent()) {
            if (open.get().getStatus() == ChangeStatus.PENDING) {
                throw new InvalidInputException("error.lifecycle.pendingLocked");
            }
            open.get().edit(json, me);
        } else {
            int number = versions.findTopByItemIdOrderByVersionNumberDesc(id).map(v -> v.getVersionNumber() + 1).orElse(1);
            versions.save(newVersion(id, number, json, me));
            record(item, "draft v" + number + " started", AuditOutcome.ALLOWED, Map.of("version", number));
        }
        if (item.getLiveVersion() == 0) {
            apply(item, content);
        }
        return item;
    }

    public void submit(UUID id, String note) {
        E item = get(id);
        currentUser.requireEdit(item.getResponsible());
        AppUser me = currentUser.require();
        V draft = openVersion(id).filter(v -> v.getStatus() == ChangeStatus.DRAFT)
                .orElseThrow(() -> new InvalidInputException("error.lifecycle.noDraft"));
        String n = requireNote(note);
        checkReady(item, parse(draft));
        draft.submit(me, n);
        if (item.getLifecycle() == Lifecycle.DRAFT) {
            item.setLifecycle(Lifecycle.IN_REVIEW);
        }
        record(item, "submitted for review by " + me.getDisplayName() + " (v" + draft.getVersionNumber() + ")", AuditOutcome.NEEDS_APPROVAL,
                Map.of("version", draft.getVersionNumber()));
    }

    /** Only the author takes a submitted version back. */
    public void withdraw(UUID id) {
        E item = get(id);
        AppUser me = currentUser.require();
        V pending = openVersion(id).filter(v -> v.getStatus() == ChangeStatus.PENDING)
                .orElseThrow(() -> new InvalidInputException("error.lifecycle.nothingPending"));
        if (!pending.getAuthor().getId().equals(me.getId())) {
            throw new AccessDeniedException("Only the author can withdraw a change");
        }
        pending.decide(ChangeStatus.WITHDRAWN, me, null, Instant.now());
        if (item.getLifecycle() == Lifecycle.IN_REVIEW) {
            item.setLifecycle(Lifecycle.DRAFT);
        }
        record(item, "change v" + pending.getVersionNumber() + " withdrawn by " + me.getDisplayName(), AuditOutcome.ALLOWED,
                Map.of("version", pending.getVersionNumber()));
    }

    /** Throws the draft away. A never approved item has no live version to fall back to: delete it instead. */
    public void discardDraft(UUID id) {
        E item = get(id);
        currentUser.requireEdit(item.getResponsible());
        V draft = openVersion(id).filter(v -> v.getStatus() == ChangeStatus.DRAFT)
                .orElseThrow(() -> new InvalidInputException("error.lifecycle.noDraft"));
        if (item.getLiveVersion() == 0) {
            throw new InvalidInputException("error.lifecycle.deleteInstead");
        }
        versions.delete(draft);
        record(item, "draft v" + draft.getVersionNumber() + " discarded", AuditOutcome.ALLOWED, Map.of("version", draft.getVersionNumber()));
    }

    // ----- the reviewer's steps (Admin, never the author) -----

    public void approve(UUID versionId, String comment) {
        AppUser me = currentUser.requireAdmin();
        V version = version(versionId);
        requirePendingByOther(me, version);
        E item = get(version.getItemId());
        S snapshot = parse(version);
        checkReady(item, snapshot);
        apply(item, snapshot);
        item.setLiveVersion(version.getVersionNumber());
        item.setLifecycle(Lifecycle.APPROVED);
        version.decide(ChangeStatus.APPROVED, me, optional(comment), Instant.now());
        record(item, "v" + version.getVersionNumber() + " approved by " + me.getDisplayName(), AuditOutcome.ALLOWED,
                Map.of("version", version.getVersionNumber(), "author", version.getAuthor().getUsername()));
    }

    public void reject(UUID versionId, String comment) {
        AppUser me = currentUser.requireAdmin();
        V version = version(versionId);
        requirePendingByOther(me, version);
        String reason = optional(comment);
        if (reason == null) {
            throw new InvalidInputException("error.lifecycle.commentRequired");
        }
        E item = get(version.getItemId());
        version.decide(ChangeStatus.REJECTED, me, reason, Instant.now());
        if (item.getLifecycle() == Lifecycle.IN_REVIEW) {
            item.setLifecycle(Lifecycle.DRAFT);
        }
        record(item, "v" + version.getVersionNumber() + " rejected by " + me.getDisplayName(), AuditOutcome.BLOCKED,
                Map.of("version", version.getVersionNumber()));
    }

    // ----- Admin, immediate -----

    public void retire(UUID id) {
        AppUser me = currentUser.requireAdmin();
        E item = get(id);
        if (item.getLifecycle() != Lifecycle.APPROVED) {
            throw new InvalidInputException("error.lifecycle.notApproved");
        }
        if (openVersion(id).isPresent()) {
            throw new InvalidInputException("error.lifecycle.openVersion");
        }
        checkUnused(item);
        item.setLifecycle(Lifecycle.RETIRED);
        record(item, "retired by " + me.getDisplayName(), AuditOutcome.ALLOWED, Map.of());
    }

    /** A retired item comes back as a new draft that has to be approved again. */
    public void restore(UUID id) {
        AppUser me = currentUser.requireAdmin();
        E item = get(id);
        if (item.getLifecycle() != Lifecycle.RETIRED) {
            throw new InvalidInputException("error.lifecycle.notRetired");
        }
        int number = versions.findTopByItemIdOrderByVersionNumberDesc(id).map(v -> v.getVersionNumber() + 1).orElse(1);
        versions.save(newVersion(id, number, JSON.writeValueAsString(snapshotOf(item)), me));
        item.setLifecycle(Lifecycle.DRAFT);
        record(item, "restored as draft v" + number + " by " + me.getDisplayName(), AuditOutcome.ALLOWED, Map.of("version", number));
    }

    /** Deletes an item that never went live, or a retired one, together with its versions. */
    public void delete(UUID id) {
        E item = get(id);
        currentUser.requireEdit(item.getResponsible());
        if (item.getLifecycle() == Lifecycle.APPROVED || item.getLifecycle() == Lifecycle.IN_REVIEW) {
            throw new InvalidInputException("error.lifecycle.cannotDelete");
        }
        checkUnused(item);
        String name = item.getName();
        beforeDelete(item);
        items.delete(item);
        audit.workspace(label + " “" + name + "” deleted by " + currentUser.require().getDisplayName(), AuditOutcome.ALLOWED, null,
                Map.of("id", id.toString()));
    }

    protected void beforeDelete(E item) {
    }

    // ----- internals -----

    private void requirePendingByOther(AppUser me, V version) {
        if (version.getStatus() != ChangeStatus.PENDING) {
            throw new InvalidInputException("error.lifecycle.nothingPending");
        }
        if (version.getAuthor().getId().equals(me.getId())) {
            throw new AccessDeniedException("A second person has to decide on your change");
        }
    }

    private static String requireNote(String note) {
        String n = note == null ? "" : note.strip();
        if (n.isEmpty()) {
            throw new InvalidInputException("error.rule.noteRequired");
        }
        if (n.length() > NOTE_MAX) {
            throw new InvalidInputException("error.tooLong", NOTE_MAX);
        }
        return n;
    }

    private static String optional(String comment) {
        String c = comment == null ? "" : comment.strip();
        if (c.length() > NOTE_MAX) {
            throw new InvalidInputException("error.tooLong", NOTE_MAX);
        }
        return c.isEmpty() ? null : c;
    }
}
