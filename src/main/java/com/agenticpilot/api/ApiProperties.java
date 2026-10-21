package com.agenticpilot.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** API limits. Rate limits are kept in memory per replica. */
@ConfigurationProperties("agenticpilot.api")
public record ApiProperties(
        @DefaultValue("60") int rateLimitPerMinute,
        @DefaultValue("20") int failedAuthPerMinute,
        @DefaultValue("262144") long maxBodyBytes) {
}
