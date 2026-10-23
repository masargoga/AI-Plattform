package com.agenticpilot.run;

import java.util.List;

/** Puts chunks of a document back together. Neighbouring chunks overlap by up to about 100 characters. */
final class ChunkText {

    static final int MIN_OVERLAP = 10;
    static final int MAX_OVERLAP = 160;

    private ChunkText() {
    }

    static String join(List<String> chunks) {
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            if (out.length() == 0) {
                out.append(chunk);
                continue;
            }
            String rest = afterOverlap(out.toString(), chunk);
            out.append(rest == null ? "\n" + chunk : rest);
        }
        return out.toString();
    }

    /**
     * What the next chunk adds after the text the previous one already ended with: the part after the longest piece where the end of
     * the previous text equals the start of the next chunk. Null when they do not overlap.
     */
    static String afterOverlap(String previous, String next) {
        int max = Math.min(Math.min(previous.length(), next.length()), MAX_OVERLAP);
        for (int k = max; k >= MIN_OVERLAP; k--) {
            if (previous.regionMatches(previous.length() - k, next, 0, k)) {
                return next.substring(k);
            }
        }
        return null;
    }
}
