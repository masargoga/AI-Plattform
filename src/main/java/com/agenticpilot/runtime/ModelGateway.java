package com.agenticpilot.runtime;

import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.runtime.provider.ChatMessage;
import com.agenticpilot.runtime.provider.ModelException;
import com.agenticpilot.runtime.provider.ModelProvider;
import com.agenticpilot.runtime.provider.ModelRequest;
import com.agenticpilot.runtime.provider.ModelResponse;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * The one place that calls a model. It maps the agent's model setting to a concrete model name, refuses EU-only requests
 * unless the operator declared the endpoint EU-approved (fail closed) and applies the timeout and the prompt size limit.
 */
@Service
public class ModelGateway {

    /** Why a call did not happen. */
    public enum Refusal { NOT_CONFIGURED, EU_NOT_AVAILABLE }

    private final ModelProvider provider;
    private final ModelProperties properties;

    public ModelGateway(ModelProvider provider, ModelProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    /** Empty when a call may go ahead, otherwise the reason it may not. */
    public java.util.Optional<Refusal> refusalFor(Region region) {
        if (!provider.isConfigured()) {
            return java.util.Optional.of(Refusal.NOT_CONFIGURED);
        }
        if (region == Region.EU && !properties.euAllowed()) {
            return java.util.Optional.of(Refusal.EU_NOT_AVAILABLE);
        }
        return java.util.Optional.empty();
    }

    public int maxPromptChars() {
        return properties.maxPromptChars();
    }

    public String modelName(ModelTier tier) {
        return switch (tier) {
            case FAST -> properties.names().fast();
            case BALANCED -> properties.names().balanced();
            case DEEP -> properties.names().deep();
        };
    }

    public ModelResponse generate(ModelTier tier, Region region, String system, List<ChatMessage> messages) {
        return stream(tier, region, system, messages, null);
    }

    /** Calls the model; with a consumer the answer is streamed to it as it arrives. */
    public ModelResponse stream(ModelTier tier, Region region, String system, List<ChatMessage> messages, Consumer<String> onText) {
        java.util.Optional<Refusal> refusal = refusalFor(region);
        if (refusal.isPresent()) {
            throw new ModelException("Model call refused: " + refusal.get());
        }
        int chars = system.length() + messages.stream().mapToInt(m -> m.text().length()).sum();
        if (chars > properties.maxPromptChars()) {
            throw new ModelException("The prompt is too large");
        }
        ModelRequest request = new ModelRequest(modelName(tier), system, messages, List.of(), properties.maxTokens(), properties.timeout());
        return onText == null ? provider.generate(request) : provider.stream(request, onText);
    }
}
