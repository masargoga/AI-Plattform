package com.agenticpilot.config;

import com.agenticpilot.runtime.ModelProperties;
import com.agenticpilot.runtime.provider.ClaudeProvider;
import com.agenticpilot.runtime.provider.ModelProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ModelConfig {

    @Bean
    @ConditionalOnMissingBean(ModelProvider.class)
    ModelProvider modelProvider(ModelProperties properties) {
        if (!"claude".equalsIgnoreCase(properties.provider())) {
            throw new IllegalStateException("Unknown model provider: " + properties.provider());
        }
        return new ClaudeProvider(properties.baseUrl(), properties.apiKey());
    }
}
