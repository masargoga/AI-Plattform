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

        public void reset() {
            calls.clear();
            configured = true;
            failing = false;
            answer = r -> "Fake answer";
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
            return new ModelResponse(answer.apply(request), List.of(), 10, 5);
        }
    }

    @Bean
    @Primary
    FakeModel fakeModel() {
        return new FakeModel();
    }
}
