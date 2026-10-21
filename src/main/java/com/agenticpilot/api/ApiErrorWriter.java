package com.agenticpilot.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@link ApiError} bodies for failures that happen before a controller runs (authentication, rate limit). */
final class ApiErrorWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApiErrorWriter() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Object id = request.getAttribute(ApiAuthenticationFilter.REQUEST_ID);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(new ApiError(code, message, id == null ? null : id.toString())));
    }
}
