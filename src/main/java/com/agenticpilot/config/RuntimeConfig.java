package com.agenticpilot.config;

import com.agenticpilot.source.connector.SafeFetcher;
import com.agenticpilot.source.connector.SourceConnector;
import com.agenticpilot.source.connector.WebsiteConnector;
import com.agenticpilot.source.connector.WebsiteProperties;
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


    @Bean(destroyMethod = "close")
    SafeFetcher safeFetcher(WebsiteProperties properties) {
        return new SafeFetcher(properties);
    }

    @Bean
    SourceConnector websiteConnector(WebsiteProperties properties, SafeFetcher fetcher) {
        return new WebsiteConnector(properties, fetcher);
    }
}
