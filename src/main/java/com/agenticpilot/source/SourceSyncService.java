package com.agenticpilot.source;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.runtime.BackgroundExecutor;
import com.agenticpilot.source.connector.FetchException;
import com.agenticpilot.source.connector.SourceConnector;
import com.agenticpilot.source.ingest.Chunker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Syncs website sources in the background: crawl, then replace the stored documents in one transaction. If a sync fails the
 * last good content stays and the source shows the reason. Only one sync per source runs at a time.
 */
@Service
public class SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncService.class);

    private final DataSourceRepository sources;
    private final SourceDocumentRepository documents;
    private final ChunkRepository chunks;
    private final SourceConnector website;
    private final AllowedDomainService allowedDomains;
    private final BackgroundExecutor executor;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;

    public SourceSyncService(DataSourceRepository sources, SourceDocumentRepository documents, ChunkRepository chunks, SourceConnector website,
                             AllowedDomainService allowedDomains, BackgroundExecutor executor, AuditService audit, CurrentUser currentUser,
                             PlatformTransactionManager txManager) {
        this.sources = sources;
        this.documents = documents;
        this.chunks = chunks;
        this.website = website;
        this.allowedDomains = allowedDomains;
        this.executor = executor;
        this.audit = audit;
        this.currentUser = currentUser;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Starts a sync in the background and returns right away with the source in the Syncing state. */
    @PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
    public DataSource start(UUID sourceId, Consumer<DataSource> whenDone) {
        String actor = currentUser.require().getUsername();
        DataSource source = tx.execute(status -> {
            DataSource s = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
            currentUser.requireEdit(s.getCreatedBy());
            if (s.getKind() != SourceKind.WEBSITE) {
                throw new InvalidInputException("error.sync.kind");
            }
            if (s.getStatus() == SyncStatus.SYNCING) {
                throw new InvalidInputException("error.sync.running");
            }
            String host = hostOf(s.getLocation());
            if (!allowedDomains.isAllowed(host)) {
                throw new InvalidInputException("error.domain.notAllowed");
            }
            s.markSyncing();
            return s;
        });
        executor.submit(() -> {
            DataSource done = runSync(sourceId, actor);
            if (whenDone != null) {
                whenDone.accept(done);
            }
        });
        return source;
    }

    /** Runs a sync now, on the calling thread. Returns the source in its new state. Used by the background task and in tests. */
    public DataSource runSync(UUID sourceId, String actor) {
        DataSource source = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
        String name = source.getName();
        String location = source.getLocation();
        try {
            SourceConnector.SyncResult result = website.sync(location, allowedDomains::isAllowed);
            return tx.execute(status -> store(sourceId, actor, result));
        } catch (FetchException e) {
            return fail(sourceId, actor, name, e.getMessage().startsWith("error.") ? com.agenticpilot.common.Messages.english(e.getMessage()) : e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Sync of source {} failed: {}", sourceId, e.getClass().getSimpleName());
            return fail(sourceId, actor, name, "The sync failed unexpectedly");
        }
    }

    private DataSource store(UUID sourceId, String actor, SourceConnector.SyncResult result) {
        DataSource source = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
        documents.findBySourceIdOrderByTitle(sourceId).forEach(documents::delete);
        documents.flush();
        int chunkCount = 0;
        for (SourceConnector.Page page : result.pages()) {
            SourceDocument doc = documents.save(new SourceDocument(sourceId, page.title(), page.uri(), sha256(page.text())));
            List<Chunk> rows = new ArrayList<>();
            int ordinal = 0;
            for (String text : Chunker.split(page.text())) {
                rows.add(new Chunk(doc.getId(), ordinal++, text.length() > Chunk.MAX_LENGTH ? text.substring(0, Chunk.MAX_LENGTH) : text));
            }
            chunks.saveAll(rows);
            chunkCount += rows.size();
        }
        source.markSynced(result.pages().size(), Instant.now());
        audit.recordAs(actor, null, null, "Website source “" + source.getName() + "” synced: " + result.pages().size() + " pages", AuditOutcome.ALLOWED, null,
                Map.of("pages", result.pages().size(), "chunks", chunkCount, "skippedByRobots", result.skippedByRobots(), "failedPages", result.failedPages()));
        return source;
    }

    private DataSource fail(UUID sourceId, String actor, String name, String reason) {
        return tx.execute(status -> {
            DataSource source = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
            source.markError(reason);
            audit.recordAs(actor, null, null, "Website source “" + name + "” sync failed", AuditOutcome.BLOCKED, null, Map.of("reason", reason));
            return source;
        });
    }

    static String hostOf(String location) {
        try {
            String host = java.net.URI.create(location.strip()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
