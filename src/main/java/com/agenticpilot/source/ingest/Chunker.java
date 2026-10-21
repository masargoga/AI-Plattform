package com.agenticpilot.source.ingest;

import java.util.ArrayList;
import java.util.List;

/** Splits text into passages of roughly 800 characters with a little overlap, preferring paragraph and sentence ends. */
public final class Chunker {

    public static final int SIZE = 800;
    public static final int OVERLAP = 100;

    private Chunker() {
    }

    public static List<String> split(String text) {
        return split(text, SIZE, OVERLAP);
    }

    static List<String> split(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        String t = text.strip();
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(start + size, t.length());
            if (end < t.length()) {
                end = breakPoint(t, start, end, size);
            }
            String chunk = t.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= t.length()) {
                break;
            }
            int next = end - overlap;
            start = Math.max(next, start + 1);
            while (start < t.length() && !Character.isWhitespace(t.charAt(start - 1)) && start < end) {
                start++;
            }
        }
        return chunks;
    }

    /** The last paragraph, sentence or word break in the final fifth of the window; the window end if there is none. */
    private static int breakPoint(String t, int start, int end, int size) {
        int from = end - size / 5;
        for (int i = end; i > from; i--) {
            if (t.charAt(i - 1) == '\n') {
                return i;
            }
        }
        for (int i = end; i > from; i--) {
            char c = t.charAt(i - 1);
            if ((c == '.' || c == '!' || c == '?') && i < t.length() && Character.isWhitespace(t.charAt(i))) {
                return i;
            }
        }
        for (int i = end; i > from; i--) {
            if (Character.isWhitespace(t.charAt(i - 1))) {
                return i;
            }
        }
        return end;
    }
}
