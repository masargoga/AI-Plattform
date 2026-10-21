package com.agenticpilot.source.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits for uploaded files. */
@ConfigurationProperties("agenticpilot.upload")
public record UploadProperties(
        @DefaultValue("26214400") long maxBytes,
        @DefaultValue("5000000") int maxChars,
        @DefaultValue("500") int maxPdfPages) {
}
