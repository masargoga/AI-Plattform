package com.agenticpilot.runtime;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Finds the passages of an agent's data sources that fit a question. Keyword search first; vector search can follow. */
public interface Retriever {

    record Retrieved(UUID chunkId, UUID sourceId, String sourceName, String documentTitle, String text, double score) {
    }

    /** Best match first. Only sources attached to the agent are searched. */
    List<Retrieved> retrieve(UUID agentId, String question, int limit);

    /** Like {@link #retrieve(UUID, String, int)}, for an explicit set of sources (an agent draft that attaches other sources). */
    List<Retrieved> retrieveFrom(Collection<UUID> sourceIds, String question, int limit);

    /** Names of the given sources that are left out of answers because they are in error or too old and set to stop and report. */
    List<String> unavailable(Collection<UUID> sourceIds);
}
