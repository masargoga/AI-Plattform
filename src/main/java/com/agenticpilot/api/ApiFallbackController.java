package com.agenticpilot.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Anything under /api that no endpoint handles answers in the API's error format: 405 for a known path with the wrong
 * method, otherwise 404.
 */
@Hidden
@RestController
class ApiFallbackController {

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final Map<PathPattern, String> ENDPOINTS = Map.of(
            PARSER.parse("/api/v1/agents"), "GET",
            PARSER.parse("/api/v1/agents/{id}/messages"), "POST",
            PARSER.parse("/api/v1/guardrails/check"), "POST",
            PARSER.parse("/api/v1/approvals/{id}"), "GET");

    @RequestMapping("/api/{*path}")
    ResponseEntity<ApiError> unknown(HttpServletRequest request) {
        Object id = request.getAttribute(ApiAuthenticationFilter.REQUEST_ID);
        String requestId = id == null ? null : id.toString();
        var path = org.springframework.http.server.PathContainer.parsePath(request.getRequestURI());
        for (Map.Entry<PathPattern, String> e : ENDPOINTS.entrySet()) {
            if (e.getKey().matches(path)) {
                return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, e.getValue())
                        .body(new ApiError("method_not_allowed", "This method is not supported here. Use " + e.getValue() + ".", requestId));
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("not_found", "No such endpoint.", requestId));
    }
}
