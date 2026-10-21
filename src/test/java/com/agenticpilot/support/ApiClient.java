package com.agenticpilot.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A minimal HTTP client for API tests. */
public final class ApiClient {

    public record Response(int status, HttpHeaders headers, String body) {

        public JsonNode json() {
            return MAPPER.readTree(body);
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;

    public ApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    public Response send(String method, String path, String token, String body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.headers(), r.body());
    }

    public Response get(String path, String token) throws Exception {
        return send("GET", path, token, null, null);
    }

    public Response post(String path, String token, String jsonBody) throws Exception {
        return send("POST", path, token, jsonBody, "application/json");
    }
}
