package com.agenticpilot.source.connector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of the website connector. allowPrivateAddresses exists only for tests that crawl a server on localhost;
 * never switch it on in a deployment, it removes the protection against requests to internal addresses.
 */
@ConfigurationProperties("agenticpilot.website")
public record WebsiteProperties(
        @DefaultValue("50") int maxPages,
        @DefaultValue("1s") Duration requestDelay,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("2097152") int maxPageBytes,
        @DefaultValue("3") int maxRedirects,
        @DefaultValue("false") boolean allowPrivateAddresses,
        @DefaultValue("AgenticPilotBot/1.0") String userAgent) {
}
