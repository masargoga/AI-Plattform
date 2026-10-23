package com.agenticpilot.runtime.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the Claude Messages API. Prompt text and the key are never written to logs or exception messages. */
public class ClaudeProvider implements ModelProvider {

    static final String API_VERSION = "2023-06-01";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public ClaudeProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        HttpResponse<String> response = send(request, false, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw failure(response.statusCode(), response.body());
        }
        try {
            JsonNode root = JSON.readTree(response.body());
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode block : root.path("content")) {
                switch (block.path("type").asString("")) {
                    case "text" -> text.append(block.path("text").asString(""));
                    case "tool_use" -> calls.add(new ToolCall(block.path("id").asString(""), block.path("name").asString(""),
                            block.path("input").toString()));
                    default -> { }
                }
            }
            return new ModelResponse(text.toString(), calls, root.path("usage").path("input_tokens").asInt(0),
                    root.path("usage").path("output_tokens").asInt(0));
        } catch (RuntimeException e) {
            throw new ModelException("The model returned an answer that could not be read", e);
        }
    }

    @Override
    public ModelResponse stream(ModelRequest request, Consumer<String> onText) {
        HttpResponse<java.io.InputStream> response = send(request, true, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            try (var in = response.body()) {
                throw failure(response.statusCode(), new String(in.readNBytes(4096), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new ModelException("The model call failed (HTTP " + response.statusCode() + ")", e);
            }
        }
        StringBuilder text = new StringBuilder();
        int[] tokens = new int[2];
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode event = JSON.readTree(line.substring(5).strip());
                switch (event.path("type").asString("")) {
                    case "content_block_delta" -> {
                        String piece = event.path("delta").path("text").asString("");
                        if (!piece.isEmpty()) {
                            text.append(piece);
                            onText.accept(piece);
                        }
                    }
                    case "message_start" -> tokens[0] = event.path("message").path("usage").path("input_tokens").asInt(0);
                    case "message_delta" -> tokens[1] = event.path("usage").path("output_tokens").asInt(0);
                    case "error" -> throw new ModelException("The model reported an error (" + event.path("error").path("type").asString("unknown") + ")");
                    default -> { }
                }
            }
        } catch (IOException e) {
            throw new ModelException("The model connection was interrupted", e);
        }
        return new ModelResponse(text.toString(), List.of(), tokens[0], tokens[1]);
    }

    private <T> HttpResponse<T> send(ModelRequest request, boolean stream, HttpResponse.BodyHandler<T> handler) {
        if (!isConfigured()) {
            throw new ModelException("No model connection is configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        if (request.system() != null && !request.system().isBlank()) {
            body.put("system", request.system());
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            messages.add(Map.of("role", m.role(), "content", content(m)));
        }
        body.put("messages", messages);
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolDefinition t : request.tools()) {
                tools.add(Map.of("name", t.name(), "description", t.description(), "input_schema", JSON.readTree(t.inputSchemaJson())));
            }
            body.put("tools", tools);
        }
        if (stream) {
            body.put("stream", true);
        }
        HttpRequest http = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(request.timeout())
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        try {
            return this.http.send(http, handler);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelException("The model did not answer in time", e);
        } catch (IOException e) {
            throw new ModelException("The model could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("The model call was interrupted", e);
        }
    }

    /** Plain text, or content blocks when the message carries tool requests or tool results. */
    static Object content(ChatMessage m) {
        if (m.toolCalls().isEmpty() && m.toolResults().isEmpty()) {
            return m.text();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (m.text() != null && !m.text().isBlank()) {
            blocks.add(Map.of("type", "text", "text", m.text()));
        }
        for (ToolCall c : m.toolCalls()) {
            blocks.add(Map.of("type", "tool_use", "id", c.id(), "name", c.name(), "input", JSON.readTree(c.inputJson().isBlank() ? "{}" : c.inputJson())));
        }
        for (ToolResult r : m.toolResults()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", r.toolCallId());
            block.put("content", r.content());
            if (r.error()) {
                block.put("is_error", true);
            }
            blocks.add(block);
        }
        return blocks;
    }

    private static ModelException failure(int status, String body) {
        String type = "unknown";
        try {
            type = JSON.readTree(body).path("error").path("type").asString("unknown");
        } catch (RuntimeException ignored) {
            // body was not JSON; keep "unknown"
        }
        return new ModelException("The model call failed (HTTP " + status + ", " + type + ")");
    }
}
