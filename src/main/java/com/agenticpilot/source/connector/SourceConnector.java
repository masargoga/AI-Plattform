package com.agenticpilot.source.connector;

import com.agenticpilot.source.SourceKind;
import java.util.List;
import java.util.function.Predicate;

/**
 * The connector SPI: checks the configuration of a source and reads its content as pages. Storing and indexing is the job of
 * the caller. Built so far: website. (Uploads are added by the user, so they need no sync.)
 */
public interface SourceConnector {

    /** One readable page of a source. */
    record Page(String uri, String title, String text) {
    }

    /** What a sync found. Notes are short remarks for the audit log, such as pages skipped because of robots.txt. */
    record SyncResult(List<Page> pages, int skippedByRobots, int failedPages) {
    }

    SourceKind kind();

    /** Throws {@link FetchException} when the location is not acceptable. */
    void validate(String location);

    /** Reads the source. The predicate says whether a host may be crawled (the Admin allow list). */
    SyncResult sync(String location, Predicate<String> hostAllowed);
}
