package com.agenticpilot.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticpilot.runtime.provider.ToolCall;
import com.agenticpilot.skill.Tool;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.ingest.IngestionService;
import com.agenticpilot.support.AsUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RunToolsTest {

    @Autowired RunTools tools;
    @Autowired DataSourceService sources;
    @Autowired IngestionService ingestion;

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    private RunTools.Context context(DataSource s) {
        RunTools.Context ctx = new RunTools.Context(List.of(new RunTools.SourceRef(s.getId(), s.getName(), SourceKind.UPLOAD, 1)), List.of());
        ctx.loaded.add(Tool.READ_SHEETS);
        ctx.loaded.add(Tool.SEARCH_DOCUMENTS);
        return ctx;
    }

    private DataSource upload(String filename, String content) {
        AsUser.owner();
        DataSource s = sources.create("Tools " + System.nanoTime(), SourceKind.UPLOAD, "files");
        ingestion.addFile(s.getId(), filename, content.getBytes(StandardCharsets.UTF_8));
        return s;
    }

    @Test
    void everyRowIsFoundCompleteEvenWhenItsChunkEndsInTheMiddleOfIt() {
        StringBuilder csv = new StringBuilder("Customer id,Name,Notes\n");
        for (int i = 1000; i < 1080; i++) {
            csv.append(i).append(",Person ").append(i).append(",").append(("note" + i + " ").repeat(24)).append("END").append(i).append('\n');
        }
        DataSource s = upload("big.csv", csv.toString());
        RunTools.Context ctx = context(s);
        for (int i = 1000; i < 1080; i += 7) {
            var outcome = tools.execute(new ToolCall("c", "lookup_rows", "{\"source\":\"" + s.getName() + "\",\"column\":\"Customer id\",\"value\":\"" + i + "\"}"), ctx);
            assertThat(outcome.error()).isFalse();
            assertThat(outcome.content()).as("row " + i).startsWith("Customer id: " + i + ";").endsWith("END" + i);
            assertThat(outcome.content().lines().count()).isEqualTo(1);
        }
    }

    @Test
    void lookupMatchesTheWholeCellAndIsCaseInsensitive() {
        DataSource s = upload("people.csv", "Customer id,Name\n47,Ann\n471,Bob\n4711,Cy\n");
        RunTools.Context ctx = context(s);
        var outcome = tools.execute(new ToolCall("c", "lookup_rows", "{\"source\":\"" + s.getName().toUpperCase() + "\",\"column\":\"customer ID\",\"value\":\"47\"}"), ctx);
        assertThat(outcome.content()).contains("Name: Ann").doesNotContain("Bob").doesNotContain("Cy");
    }

    @Test
    void readDocumentReturnsTheWholeTextWithoutTheChunkOverlap() {
        String text = ("Sentence number %d is here. ").repeat(1);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            body.append(String.format(text, i));
            if (i % 6 == 5) {
                body.append('\n');
            }
        }
        DataSource s = upload("long.txt", body.toString());
        var outcome = tools.execute(new ToolCall("c", "read_document", "{\"source\":\"" + s.getName() + "\",\"title\":\"long.txt\"}"), context(s));
        assertThat(outcome.error()).isFalse();
        for (int i = 0; i < 120; i++) {
            assertThat(outcome.content()).containsOnlyOnce("Sentence number " + i + " is here.");
        }
        var missing = tools.execute(new ToolCall("c", "read_document", "{\"source\":\"" + s.getName() + "\",\"title\":\"nope.txt\"}"), context(s));
        assertThat(missing.error()).isTrue();
        assertThat(missing.content()).contains("long.txt");
    }

    @Test
    void searchFindsPassagesAndBadArgumentsAreRefused() {
        DataSource s = upload("faq.txt", "Plans are prorated for the days left in the billing cycle zebra" + System.nanoTime() + ".");
        RunTools.Context ctx = context(s);
        assertThat(tools.execute(new ToolCall("c", "search_documents", "{\"source\":\"" + s.getName() + "\",\"query\":\"prorated billing\"}"), ctx).content()).contains("faq.txt");
        assertThat(tools.execute(new ToolCall("c", "search_documents", "{\"source\":\"" + s.getName() + "\"}"), ctx).refused()).isTrue();
        assertThat(tools.execute(new ToolCall("c", "search_documents", "not json"), ctx).refused()).isTrue();
        assertThat(tools.execute(new ToolCall("c", "finish", "{\"status\":\"maybe\",\"result\":\"x\"}"), ctx).refused()).isTrue();
        assertThat(tools.execute(new ToolCall("c", "finish", "{\"status\":\"done\"}"), ctx).refused()).isTrue();
    }

    @Test
    void chunkTextJoinsOverlappingChunks() {
        assertThat(ChunkText.join(List.of("The quick brown fox jumps over", "fox jumps over the lazy dog"))).isEqualTo("The quick brown fox jumps over the lazy dog");
        assertThat(ChunkText.join(List.of("first part", "second part"))).isEqualTo("first part\nsecond part");
        assertThat(ChunkText.afterOverlap("abc", "xyz")).isNull();
    }
}
