package com.agenticpilot.run;

import com.agenticpilot.runtime.Retriever;
import com.agenticpilot.runtime.provider.ToolCall;
import com.agenticpilot.runtime.provider.ToolDefinition;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.Tool;
import com.agenticpilot.source.SourceKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools the model may ask for in a run, and the program doing the work when it does. The model only sends structured
 * text; this class checks the request (is the tool offered, are the arguments valid, is the source attached) and reads the
 * data itself. Every tool only reads. Results are returned as text; the guardrails are applied by the executor afterwards.
 */
@Component
public class RunTools {

    public static final String USE_SKILL = "use_skill";
    public static final String FINISH = "finish";
    public static final String SEARCH_DOCUMENTS = "search_documents";
    public static final String READ_DOCUMENT = "read_document";
    public static final String LOOKUP_ROWS = "lookup_rows";

    static final int MAX_ROWS = 20;
    static final int MAX_TITLES = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A data source the agent may read. */
    public record SourceRef(UUID id, String name, SourceKind kind, int itemCount) {
    }

    /** A skill the agent may use in this run. */
    public record SkillRef(String name, String trigger, String instructions, OutputFormat format, Set<Tool> tools) {
    }

    /** What the model may use in one run. The loaded tools grow as the model picks skills. */
    public static final class Context {
        final Map<String, SourceRef> sources = new LinkedHashMap<>();
        final Map<String, SkillRef> skills = new LinkedHashMap<>();
        final Set<Tool> loaded = EnumSet.noneOf(Tool.class);
        final Set<String> loadedSkills = new LinkedHashSet<>();

        public Context(List<SourceRef> sources, List<SkillRef> skills) {
            sources.forEach(s -> this.sources.put(s.name().toLowerCase(Locale.ROOT), s));
            skills.forEach(s -> this.skills.put(s.name().toLowerCase(Locale.ROOT), s));
        }

        public List<SourceRef> sources() {
            return List.copyOf(sources.values());
        }

        public List<SkillRef> skills() {
            return List.copyOf(skills.values());
        }

        public Set<String> loadedSkills() {
            return loadedSkills;
        }
    }

    /**
     * The answer to one tool request. A refusal is a request the program did not carry out (unknown tool, tool not offered, invalid
     * arguments, unknown source); it counts against the limit of refusals. Data tools mark their result as data to be checked.
     */
    public record Outcome(String content, boolean error, boolean refused, boolean data, SkillRef loadedSkill, boolean finish, String finishStatus) {

        static Outcome ok(String content) {
            return new Outcome(content, false, false, false, null, false, null);
        }

        static Outcome data(String content) {
            return new Outcome(content, false, false, true, null, false, null);
        }

        static Outcome error(String content) {
            return new Outcome(content, true, false, false, null, false, null);
        }

        static Outcome refused(String content) {
            return new Outcome(content, true, true, false, null, false, null);
        }
    }

    private final Retriever retriever;
    private final JdbcTemplate jdbc;
    private final RunProperties props;

    public RunTools(Retriever retriever, JdbcTemplate jdbc, RunProperties props) {
        this.retriever = retriever;
        this.jdbc = jdbc;
        this.props = props;
    }

    // ----- what is offered -----

    public List<ToolDefinition> definitions(Context ctx) {
        List<ToolDefinition> defs = new ArrayList<>();
        if (!ctx.skills.isEmpty()) {
            defs.add(new ToolDefinition(USE_SKILL, "Load a skill: returns its instructions and answer format. Do this before you work on a task the skill covers.",
                    schema(Map.of("name", "The exact name of the skill"), List.of("name"))));
        }
        if (!ctx.sources.isEmpty() && ctx.loaded.contains(Tool.SEARCH_DOCUMENTS)) {
            defs.add(new ToolDefinition(SEARCH_DOCUMENTS, "Keyword search in one data source. Returns the best matching passages with the title of the document.",
                    schema(Map.of("source", "Name of the data source", "query", "Words to search for"), List.of("source", "query"))));
            defs.add(new ToolDefinition(READ_DOCUMENT, "Read one document of a data source in full (shortened if it is long).",
                    schema(Map.of("source", "Name of the data source", "title", "Title of the document"), List.of("source", "title"))));
        }
        if (!ctx.sources.isEmpty() && ctx.loaded.contains(Tool.READ_SHEETS)) {
            defs.add(new ToolDefinition(LOOKUP_ROWS, "In a spreadsheet data source, find the rows whose column has exactly this value, for example the row of a customer id.",
                    schema(Map.of("source", "Name of the data source", "column", "Name of the column, as written in the sheet header", "value", "The value to find"),
                            List.of("source", "column", "value"))));
        }
        defs.add(new ToolDefinition(FINISH, "End the run. Use status done when the goal is met, cannot_complete when it cannot be met with the tools and data you have (then explain why). Put the result for the user in result.",
                schema(Map.of("status", "done or cannot_complete", "result", "The result for the user, or the reason"), List.of("status", "result"))));
        return defs;
    }

    private static String schema(Map<String, String> properties, List<String> required) {
        Map<String, Object> props = new LinkedHashMap<>();
        properties.forEach((k, v) -> props.put(k, Map.of("type", "string", "description", v)));
        return JSON.writeValueAsString(Map.of("type", "object", "properties", props, "required", required));
    }

    // ----- doing the work -----

    public Outcome execute(ToolCall call, Context ctx) {
        JsonNode args;
        try {
            args = JSON.readTree(call.inputJson() == null || call.inputJson().isBlank() ? "{}" : call.inputJson());
        } catch (RuntimeException e) {
            return Outcome.refused("The arguments are not valid JSON.");
        }
        return switch (call.name()) {
            case USE_SKILL -> useSkill(args, ctx);
            case FINISH -> finish(args);
            case SEARCH_DOCUMENTS -> guarded(notOffered(ctx, Tool.SEARCH_DOCUMENTS, call.name()), () -> search(args, ctx));
            case READ_DOCUMENT -> guarded(notOffered(ctx, Tool.SEARCH_DOCUMENTS, call.name()), () -> read(args, ctx));
            case LOOKUP_ROWS -> guarded(notOffered(ctx, Tool.READ_SHEETS, call.name()), () -> lookup(args, ctx));
            default -> Outcome.refused("Unknown tool \"" + call.name() + "\". The tools you may use are listed with the request.");
        };
    }

    private static Outcome guarded(Outcome refusal, java.util.function.Supplier<Outcome> work) {
        return refusal != null ? refusal : work.get();
    }

    /** Null when the tool is offered: a skill that allows it has been loaded and the agent has sources. */
    private Outcome notOffered(Context ctx, Tool required, String name) {
        if (ctx.sources.isEmpty()) {
            return Outcome.refused("The tool " + name + " is not available: this agent has no data sources attached.");
        }
        if (!ctx.loaded.contains(required)) {
            return Outcome.refused("The tool " + name + " is not available yet. Load a skill that allows it with use_skill first.");
        }
        return null;
    }

    private Outcome useSkill(JsonNode args, Context ctx) {
        String name = text(args, "name");
        SkillRef skill = name == null ? null : ctx.skills.get(name.strip().toLowerCase(Locale.ROOT));
        if (skill == null) {
            return Outcome.refused("There is no skill named \"" + (name == null ? "" : name) + "\". Skills of this agent: "
                    + String.join(", ", ctx.skills.values().stream().map(SkillRef::name).toList()) + ".");
        }
        ctx.loaded.addAll(skill.tools());
        ctx.loadedSkills.add(skill.name());
        StringBuilder sb = new StringBuilder("Skill: ").append(skill.name()).append('\n');
        if (!skill.trigger().isBlank()) {
            sb.append("Use it when: ").append(skill.trigger()).append('\n');
        }
        sb.append("Answer format: ").append(skill.format().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append('\n');
        sb.append("Instructions:\n").append(skill.instructions().isBlank() ? "(none given)" : skill.instructions()).append('\n');
        List<String> offered = new ArrayList<>();
        if (ctx.loaded.contains(Tool.SEARCH_DOCUMENTS)) {
            offered.add(SEARCH_DOCUMENTS + ", " + READ_DOCUMENT);
        }
        if (ctx.loaded.contains(Tool.READ_SHEETS)) {
            offered.add(LOOKUP_ROWS);
        }
        sb.append("Data tools you may use now: ").append(offered.isEmpty() ? "none" : String.join(", ", offered)).append('.');
        Outcome o = Outcome.ok(sb.toString());
        return new Outcome(o.content(), false, false, false, skill, false, null);
    }

    private Outcome finish(JsonNode args) {
        String status = text(args, "status");
        String result = text(args, "result");
        if (status == null || !(status.equals("done") || status.equals("cannot_complete"))) {
            return Outcome.refused("finish needs status \"done\" or \"cannot_complete\".");
        }
        if (result == null || result.isBlank()) {
            return Outcome.refused("finish needs a result: the result for the user, or the reason you cannot complete.");
        }
        return new Outcome(result.strip(), false, false, false, null, true, status);
    }

    private Outcome search(JsonNode args, Context ctx) {
        SourceRef source = source(args, ctx);
        String query = text(args, "query");
        if (source == null) {
            return unknownSource(args, ctx);
        }
        if (query == null || query.isBlank() || query.length() > 200) {
            return Outcome.refused("search_documents needs a query of up to 200 characters.");
        }
        if (!retriever.unavailable(List.of(source.id())).isEmpty()) {
            return Outcome.error("The source \"" + source.name() + "\" is currently unavailable (it failed or is too old). Do not guess its content.");
        }
        List<Retriever.Retrieved> found = retriever.retrieveFrom(List.of(source.id()), query, 5);
        if (found.isEmpty()) {
            return Outcome.data("No passages found for this query in \"" + source.name() + "\". Try other words, or read_document if you know the title.");
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Retriever.Retrieved r : found) {
            sb.append('[').append(i++).append("] ").append(r.documentTitle()).append('\n').append(r.text()).append("\n\n");
        }
        return Outcome.data(sb.toString().strip());
    }

    @Transactional(readOnly = true)
    Outcome read(JsonNode args, Context ctx) {
        SourceRef source = source(args, ctx);
        String title = text(args, "title");
        if (source == null) {
            return unknownSource(args, ctx);
        }
        if (title == null || title.isBlank()) {
            return Outcome.refused("read_document needs the title of a document.");
        }
        if (!retriever.unavailable(List.of(source.id())).isEmpty()) {
            return Outcome.error("The source \"" + source.name() + "\" is currently unavailable (it failed or is too old). Do not guess its content.");
        }
        List<UUID> docs = jdbc.query("select id from source_document where source_id = ? and lower(title) = ?", (rs, n) -> rs.getObject(1, UUID.class),
                source.id(), title.strip().toLowerCase(Locale.ROOT));
        if (docs.isEmpty()) {
            List<String> titles = jdbc.query("select title from source_document where source_id = ? order by title limit " + MAX_TITLES, (rs, n) -> rs.getString(1), source.id());
            return Outcome.error("No document titled \"" + title + "\" in \"" + source.name() + "\". Documents: " + String.join(", ", titles) + ".");
        }
        List<String> chunks = jdbc.query("select text from chunk where document_id = ? order by ordinal", (rs, n) -> rs.getString(1), docs.get(0));
        return Outcome.data(ChunkText.join(chunks));
    }

    @Transactional(readOnly = true)
    Outcome lookup(JsonNode args, Context ctx) {
        SourceRef source = source(args, ctx);
        String column = text(args, "column");
        String value = text(args, "value");
        if (source == null) {
            return unknownSource(args, ctx);
        }
        if (column == null || column.isBlank() || value == null || value.isBlank() || column.length() > 100 || value.length() > 200) {
            return Outcome.refused("lookup_rows needs a column and a value.");
        }
        if (!retriever.unavailable(List.of(source.id())).isEmpty()) {
            return Outcome.error("The source \"" + source.name() + "\" is currently unavailable (it failed or is too old). Do not guess its content.");
        }
        String col = column.strip();
        String val = value.strip();
        Pattern cell = Pattern.compile("(?:^|;\\s*)" + Pattern.quote(col) + ":\\s*" + Pattern.quote(val) + "\\s*(?:;|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        String like = "%" + escapeLike((col + ": " + val).toLowerCase(Locale.ROOT)) + "%";
        record Hit(UUID documentId, int ordinal, String text) {
        }
        List<Hit> hits = jdbc.query("""
                select c.document_id, c.ordinal, c.text from chunk c join source_document d on d.id = c.document_id
                where d.source_id = ? and lower(c.text) like ? escape '\\' order by d.title, c.ordinal limit 200
                """, (rs, n) -> new Hit(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)), source.id(), like);
        Set<String> rows = new LinkedHashSet<>();
        for (Hit hit : hits) {
            String[] lines = hit.text().split("\\R");
            for (int i = 0; i < lines.length && rows.size() < MAX_ROWS; i++) {
                if (!cell.matcher(lines[i]).find()) {
                    continue;
                }
                String line = lines[i];
                if (i == lines.length - 1) {
                    line = completeLine(line, hit.documentId(), hit.ordinal());
                }
                if (cell.matcher(line).find()) {
                    rows.add(line);
                }
            }
        }
        if (rows.isEmpty()) {
            List<String> headers = jdbc.query("""
                    select c.text from chunk c join source_document d on d.id = c.document_id
                    where d.source_id = ? and lower(c.text) like '%columns: %' order by d.title, c.ordinal limit 3
                    """, (rs, n) -> rs.getString(1), source.id());
            Set<String> columns = new LinkedHashSet<>();
            for (String chunk : headers) {
                for (String line : chunk.split("\\R")) {
                    if (line.regionMatches(true, 0, "Columns:", 0, 8)) {
                        columns.add(line.strip());
                    }
                }
            }
            return Outcome.data("No row found where \"" + col + "\" is \"" + val + "\" in \"" + source.name() + "\"."
                    + (columns.isEmpty() ? " This source does not look like a spreadsheet." : " " + String.join(" ", columns)));
        }
        return Outcome.data(String.join("\n", rows));
    }

    /** A row that ends where its chunk ends may continue in the next chunk. */
    private String completeLine(String line, UUID documentId, int ordinal) {
        List<String> next = jdbc.query("select text from chunk where document_id = ? and ordinal = ?", (rs, n) -> rs.getString(1), documentId, ordinal + 1);
        if (next.isEmpty()) {
            return line;
        }
        String remainder = ChunkText.afterOverlap(line, next.get(0));
        if (remainder == null || remainder.startsWith("\n") || remainder.startsWith("\r")) {
            return line;
        }
        int end = remainder.indexOf('\n');
        return line + (end < 0 ? remainder : remainder.substring(0, end));
    }

    // ----- helpers -----

    private SourceRef source(JsonNode args, Context ctx) {
        String name = text(args, "source");
        return name == null ? null : ctx.sources.get(name.strip().toLowerCase(Locale.ROOT));
    }

    private Outcome unknownSource(JsonNode args, Context ctx) {
        String name = text(args, "source");
        return Outcome.refused("This agent has no data source named \"" + (name == null ? "" : name) + "\". Sources: "
                + String.join(", ", ctx.sources.values().stream().map(SourceRef::name).toList()) + ".");
    }

    private static String text(JsonNode args, String field) {
        JsonNode n = args.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asString("");
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    int maxResultChars() {
        return props.maxResultChars();
    }
}
