package com.agenticpilot.source.ingest;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.source.Chunk;
import com.agenticpilot.source.ChunkRepository;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.source.SourceDocument;
import com.agenticpilot.source.SourceDocumentRepository;
import com.agenticpilot.source.SourceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds uploaded files to a data source: extract the text, split it into chunks and store them for retrieval. */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class IngestionService {

    private final DataSourceRepository sources;
    private final SourceDocumentRepository documents;
    private final ChunkRepository chunks;
    private final TextExtractor extractor;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public IngestionService(DataSourceRepository sources, SourceDocumentRepository documents, ChunkRepository chunks,
                            TextExtractor extractor, CurrentUser currentUser, AuditService audit) {
        this.sources = sources;
        this.documents = documents;
        this.chunks = chunks;
        this.extractor = extractor;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SourceDocument> documents(UUID sourceId) {
        return documents.findBySourceIdOrderByTitle(sourceId);
    }

    /** Adds one file. A file with the same name replaces the earlier one; the same content twice is refused. */
    public DataSource addFile(UUID sourceId, String filename, byte[] data) {
        DataSource source = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source"));
        currentUser.requireEdit(source.getCreatedBy());
        if (source.getKind() != SourceKind.UPLOAD) {
            throw new InvalidInputException("error.upload.kind");
        }
        ExtractedText extracted = extractor.extract(filename, data);
        String hash = sha256(extracted.text());
        if (documents.findBySourceIdAndContentHash(sourceId, hash).isPresent()) {
            throw new UploadRejectedException("upload.error.duplicate");
        }
        documents.findBySourceIdOrderByTitle(sourceId).stream()
                .filter(d -> d.getTitle().equalsIgnoreCase(extracted.title())).forEach(documents::delete);
        documents.flush();

        SourceDocument doc = documents.save(new SourceDocument(sourceId, extracted.title(), null, hash));
        List<Chunk> rows = new ArrayList<>();
        int ordinal = 0;
        for (String text : Chunker.split(extracted.text())) {
            rows.add(new Chunk(doc.getId(), ordinal++, text.length() > Chunk.MAX_LENGTH ? text.substring(0, Chunk.MAX_LENGTH) : text));
        }
        chunks.saveAll(rows);
        source.markSynced(documents.countBySourceId(sourceId), Instant.now());
        audit.workspace("File added to “" + source.getName() + "”", AuditOutcome.ALLOWED, null,
                Map.of("chunks", rows.size(), "type", TextExtractor.extension(extracted.title())));
        return source;
    }

    /** Removes a document and its chunks, for example to answer a deletion request. */
    public void removeDocument(UUID documentId) {
        SourceDocument doc = documents.findById(documentId).orElseThrow(() -> new NotFoundException("Document"));
        DataSource source = sources.findById(doc.getSourceId()).orElseThrow(() -> new NotFoundException("Data source"));
        currentUser.requireEdit(source.getCreatedBy());
        documents.delete(doc);
        documents.flush();
        source.markSynced(documents.countBySourceId(source.getId()), Instant.now());
        audit.workspace("File removed from “" + source.getName() + "”", AuditOutcome.ALLOWED, null, Map.of());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
