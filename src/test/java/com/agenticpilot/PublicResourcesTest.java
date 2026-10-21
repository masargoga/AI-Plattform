package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Fonts and styles must load on the login page, so they are served without a session and from the app itself. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublicResourcesTest {

    @LocalServerPort
    int port;

    @Test
    void fontsAndStylesAreServedWithoutLogin() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (String path : new String[] {"/fonts/dm-sans-latin-wght-normal.woff2", "/styles.css"}) {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
        }
    }
}
