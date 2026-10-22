package com.agenticpilot.user;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seed password for the dummy users. Comes from AGENTICPILOT_SEED_PASSWORD, never from the repository.
 * generateIfMissing is switched on only by `mvn spring-boot:run`: without a password a random one is created and shown once on the console.
 */
@ConfigurationProperties("agenticpilot.seed")
public record SeedProperties(String password, @DefaultValue("false") boolean generateIfMissing) {
}
