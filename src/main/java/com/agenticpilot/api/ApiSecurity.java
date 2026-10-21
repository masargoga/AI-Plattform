package com.agenticpilot.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jakarta.servlet.Filter;

/** The public entry points of the API security setup, so the filter class can stay package-private. */
public final class ApiSecurity {

    public static final String AUTHORITY = ApiAuthenticationFilter.AUTHORITY;

    private ApiSecurity() {
    }

    public static Filter filter(ApiKeyAuthenticator authenticator, ApiProperties properties) {
        return new ApiAuthenticationFilter(authenticator, new RateLimiter(properties.rateLimitPerMinute()),
                new RateLimiter(properties.failedAuthPerMinute()), properties.maxBodyBytes());
    }

    public static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        ApiErrorWriter.write(request, response, 401, "unauthorized", "The API key is missing or not valid.");
    }

    public static void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiErrorWriter.write(request, response, 403, "forbidden", "This key may not do that.");
    }
}
