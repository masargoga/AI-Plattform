package com.agenticpilot.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Checks a Bearer token against the stored hashes. Used by the API filter before any permission exists. */
@Component
public class ApiKeyAuthenticator {

    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    private static final String DUMMY_HASH = ApiKeyService.hash("no-such-key");

    private final ApiKeyRepository keys;
    private final ConcurrentHashMap<UUID, Instant> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyAuthenticator(ApiKeyRepository keys) {
        this.keys = keys;
    }

    /** Empty for a malformed, unknown or revoked key. */
    @Transactional
    public Optional<ApiPrincipal> authenticate(String token) {
        if (token == null || token.length() < 3 + ApiKeyService.PREFIX_LENGTH + 2 || token.length() > 200 || !token.startsWith("ap_")) {
            return Optional.empty();
        }
        int sep = 3 + ApiKeyService.PREFIX_LENGTH;
        if (token.charAt(sep) != '_') {
            return Optional.empty();
        }
        String prefix = token.substring(3, sep);
        String secret = token.substring(sep + 1);
        Optional<ApiKey> found = keys.findByPrefix(prefix);
        String expected = found.map(ApiKey::getSecretHash).orElse(DUMMY_HASH);
        boolean same = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), ApiKeyService.hash(secret).getBytes(StandardCharsets.UTF_8));
        if (found.isEmpty() || !same || found.get().isRevoked()) {
            return Optional.empty();
        }
        ApiKey key = found.get();
        Instant now = Instant.now();
        Instant last = lastTouched.get(key.getId());
        if (last == null || Duration.between(last, now).compareTo(TOUCH_EVERY) > 0) {
            key.markUsed(now);
            lastTouched.put(key.getId(), now);
        }
        return Optional.of(new ApiPrincipal(key.getId(), key.getPrefix(), key.getAgentId()));
    }
}
