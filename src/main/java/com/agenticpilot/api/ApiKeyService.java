package com.agenticpilot.api;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.NotFoundException;
import com.agenticpilot.user.AppUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating, listing and revoking API keys. Owners and Admins manage the keys of their agents. */
@Service
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','AGENT_OWNER')")
public class ApiKeyService {

    static final String PREFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    static final int PREFIX_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The stored key and the full token. The token exists only in this object, once. */
    public record CreatedKey(ApiKey key, String token) {
    }

    private final ApiKeyRepository keys;
    private final AgentRepository agents;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public ApiKeyService(ApiKeyRepository keys, AgentRepository agents, AuditService audit, CurrentUser currentUser) {
        this.keys = keys;
        this.agents = agents;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID agentId) {
        Agent agent = agent(agentId);
        currentUser.requireEdit(agent.getOwner());
        return keys.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public CreatedKey create(UUID agentId, String name) {
        Agent agent = agent(agentId);
        AppUser me = currentUser.require();
        currentUser.requireEdit(agent.getOwner());
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new InvalidInputException("error.nameRequired");
        }
        if (n.length() > 80) {
            throw new InvalidInputException("error.tooLong", 80);
        }
        String prefix = randomPrefix();
        String secret = randomSecret();
        ApiKey key = keys.save(new ApiKey(agentId, n, prefix, hash(secret), me));
        audit.record(agentId, agent.getName(), "API key “" + n + "” created", AuditOutcome.ALLOWED, null, Map.of("prefix", prefix));
        return new CreatedKey(key, "ap_" + prefix + "_" + secret);
    }

    public void revoke(UUID keyId) {
        ApiKey key = keys.findById(keyId).orElseThrow(() -> new NotFoundException("API key"));
        Agent agent = agent(key.getAgentId());
        currentUser.requireEdit(agent.getOwner());
        if (!key.isRevoked()) {
            key.revoke(Instant.now());
            audit.record(agent.getId(), agent.getName(), "API key “" + key.getName() + "” revoked", AuditOutcome.ALLOWED, null, Map.of("prefix", key.getPrefix()));
        }
    }

    private Agent agent(UUID id) {
        return agents.findById(id).orElseThrow(() -> new NotFoundException("Agent"));
    }

    static String randomPrefix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PREFIX_LENGTH; i++) {
            sb.append(PREFIX_ALPHABET.charAt(RANDOM.nextInt(PREFIX_ALPHABET.length())));
        }
        return sb.toString();
    }

    static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 is enough here: the secret is 256 bits of randomness, so there is nothing to brute-force. */
    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
