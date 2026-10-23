package com.agenticpilot.runtime.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ClaudeToolMessagesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void plainMessagesStayText() {
        assertThat(ClaudeProvider.content(ChatMessage.user("hello"))).isEqualTo("hello");
    }

    @Test
    void toolRequestsAndResultsBecomeContentBlocks() {
        Object assistant = ClaudeProvider.content(ChatMessage.assistantWithTools("Let me look", List.of(new ToolCall("t1", "lookup_rows", "{\"value\":\"4711\"}"))));
        var blocks = JSON.readTree(JSON.writeValueAsString(assistant));
        assertThat(blocks.size()).isEqualTo(2);
        assertThat(blocks.get(0).path("type").asString()).isEqualTo("text");
        assertThat(blocks.get(1).path("type").asString()).isEqualTo("tool_use");
        assertThat(blocks.get(1).path("id").asString()).isEqualTo("t1");
        assertThat(blocks.get(1).path("input").path("value").asString()).isEqualTo("4711");

        Object user = ClaudeProvider.content(ChatMessage.toolResults(List.of(new ToolResult("t1", "row", false), new ToolResult("t2", "no such tool", true))));
        var results = JSON.readTree(JSON.writeValueAsString(user));
        assertThat(results.get(0).path("type").asString()).isEqualTo("tool_result");
        assertThat(results.get(0).path("tool_use_id").asString()).isEqualTo("t1");
        assertThat(results.get(0).has("is_error")).isFalse();
        assertThat(results.get(1).path("is_error").asBoolean()).isTrue();
    }

    @Test
    void anEmptyToolInputBecomesAnEmptyObject() {
        Object assistant = ClaudeProvider.content(ChatMessage.assistantWithTools("", List.of(new ToolCall("t1", "finish", ""))));
        var blocks = JSON.readTree(JSON.writeValueAsString(assistant));
        assertThat(blocks.size()).isEqualTo(1);
        assertThat(blocks.get(0).path("input").isObject()).isTrue();
    }

    @Test
    void messageSizeCountsToolTraffic() {
        assertThat(ChatMessage.toolResults(List.of(new ToolResult("t", "12345", false))).size()).isEqualTo(5);
        assertThat(ChatMessage.assistantWithTools("ab", List.of(new ToolCall("t", "tool", "{}"))).size()).isEqualTo(2 + 4 + 2);
        assertThat(Map.of()).isEmpty();
    }
}
