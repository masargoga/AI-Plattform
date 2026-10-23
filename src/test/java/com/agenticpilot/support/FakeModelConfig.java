package com.agenticpilot.support;

import com.agenticpilot.runtime.provider.ModelProvider;
import com.agenticpilot.runtime.provider.ModelRequest;
import com.agenticpilot.runtime.provider.ModelResponse;
import com.agenticpilot.runtime.provider.ModelException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A model provider for tests: records every request and answers with a configurable function. */
@TestConfiguration
public class FakeModelConfig {

    public static class FakeModel implements ModelProvider {
        public final List<ModelRequest> calls = new CopyOnWriteArrayList<>();
        public volatile boolean configured = true;
        public volatile boolean failing = false;
        public volatile Function<ModelRequest, String> answer = r -> "Fake answer";
        /** When set, decides the whole response (text, tool requests, token counts). Used to script agent runs. */
        public volatile Function<ModelRequest, ModelResponse> responder;

        public void reset() {
            calls.clear();
            configured = true;
            failing = false;
            answer = r -> "Fake answer";
            responder = null;
        }

        /** The next responses in order; the last one repeats. */
        public void script(ModelResponse... responses) {
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            responder = r -> responses[Math.min(next.getAndIncrement(), responses.length - 1)];
        }

        public static ModelResponse tool(String name, String json) {
            return new ModelResponse("", List.of(new com.agenticpilot.runtime.provider.ToolCall("call_" + System.nanoTime(), name, json)), 100, 20);
        }

        public static ModelResponse tools(com.agenticpilot.runtime.provider.ToolCall... calls) {
            return new ModelResponse("", List.of(calls), 100, 20);
        }

        public static com.agenticpilot.runtime.provider.ToolCall call(String name, String json) {
            return new com.agenticpilot.runtime.provider.ToolCall("call_" + System.nanoTime(), name, json);
        }

        public static ModelResponse text(String text) {
            return new ModelResponse(text, List.of(), 100, 20);
        }

        public ModelRequest last() {
            return calls.get(calls.size() - 1);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            calls.add(request);
            if (failing) {
                throw new ModelException("The model call failed (HTTP 500, api_error)");
            }
            if (responder != null) {
                return responder.apply(request);
            }
            return new ModelResponse(answer.apply(request), List.of(), 10, 5);
        }
    }

    @Bean
    @Primary
    FakeModel fakeModel() {
        return new FakeModel();
    }
}
