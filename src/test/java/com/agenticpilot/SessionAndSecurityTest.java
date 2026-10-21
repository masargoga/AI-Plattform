package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agenticpilot.common.JobLock;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The multi-replica promises: a user stays signed in on another replica, sign-out is final, background jobs run once. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionAndSecurityTest {

    private static final String COOKIE = "ap-remember-me";

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JobLock lock;

    private Cookie signIn(String user) throws Exception {
        MockHttpServletResponse response = mvc.perform(formLogin("/login").user(user).password("test-only-password"))
                .andReturn().getResponse();
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).as("remember-me cookie after login").isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return cookie;
    }

    /** True if a replica without a session accepts the cookie: it signs the user in and hands out the rotated token. */
    private boolean accepted(Cookie cookie) throws Exception {
        var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/agents"))
                .header("Cookie", cookie.getName() + "=" + cookie.getValue()).GET().build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        return response.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith(COOKIE + "=") && !c.startsWith(COOKIE + "=;"));
    }

    @Test
    void anotherReplicaRecognisesTheUserFromTheSharedToken() throws Exception {
        Cookie cookie = signIn("admin");
        assertThat(accepted(cookie)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = 'admin'", Integer.class)).isPositive();
    }

    @Test
    void unknownOrForgedTokenIsNotAccepted() throws Exception {
        assertThat(accepted(new Cookie(COOKIE, "Zm9yZ2Vk"))).isFalse();
    }

    @Test
    void signOutDeletesTheUsersTokens() throws Exception {
        var login = mvc.perform(formLogin("/login").user("owner").password("test-only-password")).andReturn();
        Cookie cookie = login.getResponse().getCookie(COOKIE);
        mvc.perform(post("/logout").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession()).cookie(cookie)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()));
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = 'owner'", Integer.class)).isZero();
        assertThat(accepted(cookie)).isFalse();
    }

    @Test
    void securityHeadersAreSetOnTheLoginPage() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/actuator/health")).andReturn().getResponse();
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("same-origin");
        assertThat(r.getHeader("Content-Security-Policy")).contains("frame-ancestors");
    }

    @Test
    void lockoutAfterRepeatedFailuresBlocksEvenTheRightPassword() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(formLogin("/login").user("admin2").password("wrong-" + i));
        }
        MockHttpServletResponse r = mvc.perform(formLogin("/login").user("admin2").password("test-only-password")).andReturn().getResponse();
        assertThat(r.getRedirectedUrl()).contains("error");
        assertThat(r.getCookie(COOKIE) == null || r.getCookie(COOKIE).getMaxAge() == 0).isTrue();
    }

    @Test
    void aBackgroundJobRunsOnlyOnceAtATime() {
        AtomicInteger inner = new AtomicInteger();
        boolean outer = lock.tryRun("test-job", Duration.ofMinutes(1), () -> {
            assertThat(lock.tryRun("test-job", Duration.ofMinutes(1), inner::incrementAndGet)).isFalse();
        });
        assertThat(outer).isTrue();
        assertThat(inner.get()).isZero();
        // released afterwards
        assertThat(lock.tryRun("test-job", Duration.ofMinutes(1), inner::incrementAndGet)).isTrue();
        assertThat(inner.get()).isEqualTo(1);
    }

    @Test
    void anExpiredLockCanBeTakenOver() {
        jdbc.update("delete from job_lock where name = 'stale-job'");
        jdbc.update("insert into job_lock (name, locked_until, locked_by) values ('stale-job', ?, 'dead-replica')",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(300)));
        assertThat(lock.tryRun("stale-job", Duration.ofMinutes(1), () -> { })).isTrue();
    }
}
