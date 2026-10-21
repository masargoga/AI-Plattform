package com.agenticpilot.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Seed password for the dummy users. Comes from AGENTICPILOT_SEED_PASSWORD, never from the repository. */
@ConfigurationProperties("agenticpilot.seed")
public record SeedProperties(String password) {
}
