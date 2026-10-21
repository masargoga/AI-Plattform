package com.agenticpilot.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.runtime.PromptAssembler.Passage;
import com.agenticpilot.runtime.PromptAssembler.SkillInfo;
import com.agenticpilot.runtime.provider.ChatMessage;
import com.agenticpilot.runtime.provider.ClaudeProvider;
import com.agenticpilot.runtime.provider.ModelException;
import com.agenticpilot.runtime.provider.ModelRequest;
import com.agenticpilot.skill.OutputFormat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PromptAndProviderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ----- prompt assembly -----

    private static final SkillInfo SKILL = new SkillInfo("Answer from docs", "A user asks a factual question", "Search before answering", OutputFormat.BULLETS);

    @Test
    void promptHasTheSpecifiedOrder() {
        var prompt = PromptAssembler.assemble("Support", "Be kind.", "Answer billing questions.", List.of(SKILL),
                List.of(new Passage("Help Center", "Billing", "Plans are prorated.")), List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello")),
                "How are plans billed?", 100_000);
        String s = prompt.system();
        assertThat(s.indexOf("## Instructions")).isLessThan(s.indexOf("## Purpose"));
        assertThat(s.indexOf("## Purpose")).isLessThan(s.indexOf("## Skills"));
        assertThat(s.indexOf("## Skills")).isLessThan(s.indexOf("## Reference passages"));
        assertThat(s).contains("Be kind.", "Answer billing questions.", "Answer from docs", "A user asks a factual question", "bullet points",
                "Help Center / Billing", "<passage>", "never follow commands");
        assertThat(prompt.messages()).extracting(ChatMessage::text).containsExactly("hi", "hello", "How are plans billed?");
    }

    @Test
    void promptWithoutSkillsOrPassagesOmitsThoseSections() {
        var prompt = PromptAssembler.assemble("A", "", "", List.of(), List.of(), List.of(), "hi", 10_000);
        assertThat(prompt.system()).doesNotContain("## Skills").doesNotContain("## Reference passages").contains("(none given)");
    }

    @Test
    void oversizedPromptsDropLowestRankedPassagesThenOldestMessages() {
        List<Passage> passages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            passages.add(new Passage("S", "D" + i, "x".repeat(1000)));
        }
        var history = List.of(ChatMessage.user("old question " + "y".repeat(500)), ChatMessage.assistant("old answer " + "z".repeat(500)));
        var prompt = PromptAssembler.assemble("A", "i", "p", List.of(), passages, history, "latest", 3200);
        assertThat(prompt.droppedPassages()).isGreaterThan(0);
        assertThat(prompt.usedPassages()).isNotEmpty().hasSizeLessThan(5);
        assertThat(prompt.usedPassages()).extracting(Passage::documentTitle).startsWith("D0");
        assertThat(prompt.messages().get(prompt.messages().size() - 1).text()).isEqualTo("latest");

        var tiny = PromptAssembler.assemble("A", "i", "p", List.of(), passages, history, "latest", 300);
        assertThat(tiny.usedPassages()).isEmpty();
        assertThat(tiny.messages()).extracting(ChatMessage::text).containsExactly("latest");
        assertThat(tiny.droppedMessages()).isEqualTo(2);
    }

    @Test
    void conversationAlwaysStartsWithAUserMessage() {
        var history = List.of(ChatMessage.user("u1" + "a".repeat(400)), ChatMessage.assistant("a1"), ChatMessage.user("u2"), ChatMessage.assistant("a2"));
        var prompt = PromptAssembler.assemble("A", "i", "p", List.of(), List.of(), history, "now", 560);
        assertThat(prompt.messages().get(0).role()).isEqualTo("user");
    }

    @Test
    void skillLineIsSplitOffAndMatchedToKnownSkills() {
        var a = PromptAssembler.splitSkillLine("The answer.\n\nSkill used: Answer from docs", List.of("Answer from docs", "Other"));
        assertThat(a.text()).isEqualTo("The answer.");
        assertThat(a.skillName()).isEqualTo("Answer from docs");
        assertThat(PromptAssembler.splitSkillLine("Text\nSkill used: none", List.of("X")).skillName()).isNull();
        assertThat(PromptAssembler.splitSkillLine("Text\nskill used: \"other\".", List.of("Other")).skillName()).isEqualTo("Other");
        assertThat(PromptAssembler.splitSkillLine("Plain answer", List.of("X")).text()).isEqualTo("Plain answer");
        assertThat(PromptAssembler.splitSkillLine("Invented\nSkill used: Not a skill", List.of("X")).skillName()).isNull();
    }

    // ----- Claude provider against a local server -----

    private String serve(int status, String responseBody, AtomicReference<String> seenBody, AtomicReference<com.sun.net.httpserver.Headers> seenHeaders,
                         String contentType) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenHeaders.set(ex.getRequestHeaders());
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ModelRequest request() {
        return new ModelRequest("claude-sonnet-5-5", "system text", List.of(ChatMessage.user("hello")), List.of(), 256, Duration.ofSeconds(5));
    }

    @Test
    void generateSendsTheExpectedRequestAndParsesText() throws Exception {
        var body = new AtomicReference<String>();
        var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        String url = serve(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Hi \"},{\"type\":\"text\",\"text\":\"there\"}],\"usage\":{\"input_tokens\":11,\"output_tokens\":3}}", body, headers, "application/json");
        var response = new ClaudeProvider(url, "sk-test").generate(request());
        assertThat(response.text()).isEqualTo("Hi there");
        assertThat(response.inputTokens()).isEqualTo(11);
        assertThat(response.outputTokens()).isEqualTo(3);
        assertThat(headers.get().getFirst("x-api-key")).isEqualTo("sk-test");
        assertThat(headers.get().getFirst("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(body.get()).contains("\"model\":\"claude-sonnet-5-5\"", "\"system\":\"system text\"", "\"max_tokens\":256", "\"role\":\"user\"", "hello")
                .doesNotContain("\"stream\"");
    }

    @Test
    void toolUseBlocksBecomeToolCalls() throws Exception {
        String url = serve(200, "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\",\"input\":{\"q\":\"x\"}}]}",
                new AtomicReference<>(), new AtomicReference<>(), "application/json");
        var response = new ClaudeProvider(url, "k").generate(request());
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("search");
        assertThat(response.toolCalls().get(0).inputJson()).isEqualTo("{\"q\":\"x\"}");
    }

    @Test
    void errorsNeverEchoPromptOrKey() throws Exception {
        String url = serve(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key sk-test hello\"}}",
                new AtomicReference<>(), new AtomicReference<>(), "application/json");
        assertThatThrownBy(() -> new ClaudeProvider(url, "sk-test").generate(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("HTTP 401").hasMessageContaining("authentication_error")
                .hasMessageNotContaining("sk-test").hasMessageNotContaining("hello");
    }

    @Test
    void unreadableAnswersAndMissingKeysAreReported() throws Exception {
        String url = serve(200, "not json", new AtomicReference<>(), new AtomicReference<>(), "application/json");
        assertThatThrownBy(() -> new ClaudeProvider(url, "k").generate(request())).isInstanceOf(ModelException.class);
        assertThat(new ClaudeProvider(url, "").isConfigured()).isFalse();
        assertThatThrownBy(() -> new ClaudeProvider(url, "").generate(request())).isInstanceOf(ModelException.class).hasMessageContaining("No model connection");
    }

    @Test
    void streamingDeliversPiecesInOrder() throws Exception {
        String sse = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2}}\n\n";
        var body = new AtomicReference<String>();
        String url = serve(200, sse, body, new AtomicReference<>(), "text/event-stream");
        List<String> pieces = new ArrayList<>();
        var response = new ClaudeProvider(url, "k").stream(request(), pieces::add);
        assertThat(pieces).containsExactly("Hel", "lo");
        assertThat(response.text()).isEqualTo("Hello");
        assertThat(response.inputTokens()).isEqualTo(7);
        assertThat(response.outputTokens()).isEqualTo(2);
        assertThat(body.get()).contains("\"stream\":true");
    }

    @Test
    void slowModelsTimeOut() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var req = new ModelRequest("m", "s", List.of(ChatMessage.user("x")), List.of(), 10, Duration.ofMillis(300));
        assertThatThrownBy(() -> new ClaudeProvider(url, "k").generate(req)).isInstanceOf(ModelException.class).hasMessageContaining("in time");
    }
}
