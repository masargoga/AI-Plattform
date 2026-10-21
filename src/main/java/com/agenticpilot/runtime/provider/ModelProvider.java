package com.agenticpilot.runtime.provider;

import java.util.function.Consumer;

/** Pluggable model access: messages and tool definitions in, text and tool calls out. Claude is the first implementation. */
public interface ModelProvider {

    /** True when the provider has what it needs (for example an API key). */
    boolean isConfigured();

    ModelResponse generate(ModelRequest request);

    /** Streams the answer in pieces. The default sends the whole answer as one piece. */
    default ModelResponse stream(ModelRequest request, Consumer<String> onText) {
        ModelResponse response = generate(request);
        onText.accept(response.text());
        return response;
    }
}
