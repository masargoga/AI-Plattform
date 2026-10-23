package com.agenticpilot.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keyword search in the database: candidates contain at least one search term, then they are ranked by term counts. */
@Component
public class KeywordRetriever implements Retriever {

    static final int MAX_TERMS = 12;
    static final int MAX_CANDIDATES = 400;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one", "our",
            "out", "has", "have", "with", "this", "that", "from", "they", "what", "when", "where", "which", "who", "how", "why", "will",
            "would", "could", "should", "about", "into", "than", "then", "there", "their", "your", "does", "did", "any", "mid", "per",
            "der", "die", "das", "und", "ist", "ein", "eine", "nicht", "mit", "von", "den", "dem", "auf", "für");

    private final JdbcTemplate jdbc;

    public KeywordRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Retrieved> retrieve(UUID agentId, String question, int limit) {
        return retrieveFrom(jdbc.query("select source_id from agent_source where agent_id = ?", (rs, n) -> rs.getObject(1, UUID.class), agentId),
                question, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Retrieved> retrieveFrom(Collection<UUID> allSourceIds, String question, int limit) {
        List<String> terms = terms(question);
        Set<UUID> left = unavailableIds(allSourceIds);
        List<UUID> sourceIds = allSourceIds.stream().filter(id -> !left.contains(id)).toList();
        if (terms.isEmpty() || limit <= 0 || sourceIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select c.id, s.id, s.name, d.title, c.text
                from chunk c
                join source_document d on d.id = c.document_id
                join data_source s on s.id = d.source_id
                where s.id in (""");
        List<Object> args = new ArrayList<>(sourceIds);
        sql.append(String.join(", ", java.util.Collections.nCopies(sourceIds.size(), "?"))).append(") and (");
        for (int i = 0; i < terms.size(); i++) {
            sql.append(i == 0 ? "" : " or ").append("lower(c.text) like ? escape '\\'");
            args.add("%" + escapeLike(terms.get(i)) + "%");
        }
        sql.append(") limit ").append(MAX_CANDIDATES);

        List<Retrieved> found = jdbc.query(sql.toString(), (rs, n) -> {
            String text = rs.getString(5);
            String title = rs.getString(4);
            return new Retrieved(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), title, text, score(terms, text, title));
        }, args.toArray());
        return found.stream().filter(r -> r.score() > 0)
                .sorted(Comparator.comparingDouble(Retrieved::score).reversed().thenComparing(Retrieved::documentTitle))
                .limit(limit).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> unavailable(Collection<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = unavailableIds(sourceIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select name from data_source where id in (" + String.join(", ", java.util.Collections.nCopies(ids.size(), "?")) + ") order by name",
                (rs, n) -> rs.getString(1), ids.toArray());
    }

    private Set<UUID> unavailableIds(Collection<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Set.of();
        }
        java.time.Instant now = java.time.Instant.now();
        Set<UUID> result = new LinkedHashSet<>();
        jdbc.query("select id, status, last_synced, max_age_days from data_source where on_failure = 'STOP_AND_REPORT' and id in ("
                + String.join(", ", java.util.Collections.nCopies(sourceIds.size(), "?")) + ")", rs -> {
            java.sql.Timestamp synced = rs.getTimestamp(3);
            boolean stale = synced == null
                    || java.time.Duration.between(synced.toInstant(), now).compareTo(java.time.Duration.ofDays(rs.getInt(4))) > 0;
            if ("ERROR".equals(rs.getString(2)) || stale) {
                result.add(rs.getObject(1, UUID.class));
            }
        }, sourceIds.toArray());
        return result;
    }

    /** Lower-case search words of at least three letters, without common words, at most {@value #MAX_TERMS}. */
    public static List<String> terms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(question == null ? "" : question.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_TERMS) {
            if (!STOP.contains(m.group())) {
                terms.add(m.group());
            }
        }
        return new ArrayList<>(terms);
    }

    /** Distinct matching terms count most, repeated hits count a little, a hit in the title adds a bonus. */
    public static double score(List<String> terms, String text, String title) {
        String lower = text.toLowerCase(Locale.ROOT);
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            int count = occurrences(lower, term);
            if (count > 0) {
                score += 1 + Math.min(count - 1, 4) * 0.2;
            }
            if (lowerTitle.contains(term)) {
                score += 0.5;
            }
        }
        return score;
    }

    private static int occurrences(String text, String term) {
        int count = 0;
        for (int i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + term.length())) {
            count++;
        }
        return count;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
