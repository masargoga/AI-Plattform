package com.agenticpilot.runtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Model settings. The API key comes from AGENTICPILOT_MODEL_API_KEY (a Kubernetes Secret) and is never logged.
 * euAllowed must be set to true by the operator only when the configured endpoint is approved for EU-only processing;
 * until then agents set to "EU only" refuse to run.
 */
@ConfigurationProperties("agenticpilot.model")
public record ModelProperties(
        @DefaultValue("claude") String provider,
        String apiKey,
        @DefaultValue("https://api.anthropic.com") String baseUrl,
        @DefaultValue("false") boolean euAllowed,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("1024") int maxTokens,
        @DefaultValue("60000") int maxPromptChars,
        @DefaultValue Names names) {

    /** Concrete model names for the agent's Fast, Balanced and Most capable settings. */
    public record Names(
            @DefaultValue("claude-haiku-5-5") String fast,
            @DefaultValue("claude-sonnet-5-5") String balanced,
            @DefaultValue("claude-opus-5-5") String deep) {
    }
}
