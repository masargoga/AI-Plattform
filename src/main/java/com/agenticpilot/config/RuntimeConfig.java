package com.agenticpilot.config;

import com.agenticpilot.source.ingest.TextExtractor;
import com.agenticpilot.source.ingest.UploadProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class RuntimeConfig {

    @Bean
    TextExtractor textExtractor(UploadProperties limits) {
        return new TextExtractor(limits);
    }

}
