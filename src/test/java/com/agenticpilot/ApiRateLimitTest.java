package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticpilot.agent.AgentService;
import com.agenticpilot.api.ApiKeyService;
import com.agenticpilot.support.ApiClient;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.support.FakeModelConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agenticpilot.api.rate-limit-per-minute=5", "agenticpilot.api.failed-auth-per-minute=3"})
@ActiveProfiles("test")
@Import(FakeModelConfig.class)
class ApiRateLimitTest {

    @LocalServerPort int port;
    @Autowired AgentService agents;
    @Autowired ApiKeyService keys;

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    @Test
    void callsAreLimitedPerKeyAndTheLimitsAreReported() throws Exception {
        AsUser.owner();
        var agent = agents.create("Limited " + System.nanoTime(), "j", List.of(), List.of());
        String keyA = keys.create(agent.getId(), "a").token();
        String keyB = keys.create(agent.getId(), "b").token();
        ApiClient api = new ApiClient(port);

        for (int i = 0; i < 5; i++) {
            var ok = api.get("/api/v1/agents", keyA);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.header("X-RateLimit-Limit")).isEqualTo("5");
            assertThat(ok.header("X-RateLimit-Remaining")).isEqualTo(String.valueOf(4 - i));
        }
        var limited = api.get("/api/v1/agents", keyA);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json().get("code").asString()).isEqualTo("rate_limited");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        // another key is not affected
        assertThat(api.get("/api/v1/agents", keyB).status()).isEqualTo(200);
    }

    @Test
    void guessingKeysIsSlowedDownPerClientAddress() throws Exception {
        ApiClient api = new ApiClient(port);
        int unauthorized = 0;
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            int status = api.get("/api/v1/agents", "ap_abcdefgh_guess" + i).status();
            if (status == 401) {
                unauthorized++;
            } else if (status == 429) {
                limited++;
            }
        }
        assertThat(unauthorized).isEqualTo(3);
        assertThat(limited).isEqualTo(5);
    }
}
