package com.agenticpilot.runtime;

import java.util.ArrayList;
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
        List<String> terms = terms(question);
        if (terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select c.id, s.id, s.name, d.title, c.text
                from chunk c
                join source_document d on d.id = c.document_id
                join data_source s on s.id = d.source_id
                join agent_source a on a.source_id = s.id
                where a.agent_id = ? and (""");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
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
