package com.agenticpilot.runtime;

import java.util.List;
import java.util.UUID;

/** Finds the passages of an agent's data sources that fit a question. Keyword search first; vector search can follow. */
public interface Retriever {

    record Retrieved(UUID chunkId, UUID sourceId, String sourceName, String documentTitle, String text, double score) {
    }

    /** Best match first. Only sources attached to the agent are searched. */
    List<Retrieved> retrieve(UUID agentId, String question, int limit);
}
